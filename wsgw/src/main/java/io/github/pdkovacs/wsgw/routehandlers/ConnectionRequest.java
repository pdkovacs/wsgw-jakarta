package io.github.pdkovacs.wsgw.routehandlers;

import io.github.pdkovacs.wsgw.AppPaths;
import io.github.pdkovacs.wsgw.socket.ConnectionIdProvider;
import io.github.pdkovacs.wsgw.WsgwPaths;
import io.github.pdkovacs.wsgw.appward.Request;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class ConnectionRequest extends HttpFilter {

    private static final CtxLogger logger = CtxLogger.of(ConnectionRequest.class);

    private record Meters(AtomicInteger inFlightConnects) {
        static Meters create(MeterRegistry registry) {
            // CONNECT
            // -- in-flight
            AtomicInteger inFlightConnects = new AtomicInteger(0);
            Gauge.builder("wsgw.connects.inflight", inFlightConnects, AtomicInteger::get)
                    .tag("leg", "connect")
                    .register(registry);

            return new Meters(inFlightConnects);
        }
    }

    private final Request appwardRequest;
    private final ConnectionIdProvider connectionIdProvider;
    private final Duration connectWaitTimeout;
    private final Meters meters;

    public ConnectionRequest(
            Request appwardRequest,
            ConnectionIdProvider connectionIdProvider,
            Duration connectWaitTimeout,
            MeterRegistry meterRegistry) {
        this.appwardRequest = appwardRequest;
        this.connectionIdProvider = connectionIdProvider;
        this.connectWaitTimeout = connectWaitTimeout;
        this.meters = Meters.create(meterRegistry);
    }

    @Override
    protected void doFilter(HttpServletRequest req,
                            HttpServletResponse res,
                            FilterChain chain) throws IOException, ServletException {
        String path = req.getServletPath();
        if (!path.startsWith(WsgwPaths.CONNECT_FROM_CLIENT)) {
            res.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }

        var reqHeaders = Request.getRequestHeaders(req);

        var connectionId = this.connectionIdProvider.generateId();
        var mLogger = logger.with("connectionId",connectionId);

        int appStatus;
        try {
            meters.inFlightConnects.incrementAndGet();
            appStatus = registerWithApp(reqHeaders, connectionId); // blocking; cheap on a virtual thread
        } catch (HttpTimeoutException timeoutException) {
            res.sendError(HttpServletResponse.SC_GATEWAY_TIMEOUT, "request timed out");
            return;
        } catch (Exception e) {
            res.sendError(HttpServletResponse.SC_BAD_GATEWAY, "failed to reach application");
            return;
        } finally {
            meters.inFlightConnects.decrementAndGet();
        }

        if (appStatus != HttpServletResponse.SC_NO_CONTENT) {
            if (appStatus == HttpServletResponse.SC_UNAUTHORIZED) {
                res.setHeader("WWW-Authenticate", "Basic realm=\"wsgw\""); // same RFC-7235 dance
                res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            } else {
                res.sendError(HttpServletResponse.SC_BAD_GATEWAY);
            }
            return; // client never sees a WS handshake — parity with Go wsgw
        }

        // Accepted: hand the id to the endpoint by injecting a header the
        // Configurator can read during the handshake, then let the upgrade proceed.
        chain.doFilter(new ConnIdRequestWrapper(req, connectionId), res);
    }

    // Relays the client's connection request to the backend's /connect endpoint
    // and returns the HTTP status the backend answered with. 204 means accepted.
    // The response body is discarded -- only the status code matters here.
    private int registerWithApp(Map<String, List<String>> reqHeaders, String connectionId) throws Exception {
        var log = ConnectionRequest.logger.with("connId", connectionId).with("appwardRquest", appwardRequest);
        log.debug("About to register with app");
        HttpResponse<Void> response = appwardRequest.send(reqHeaders,
                AppPaths.CONNECT_FROM_WSGW + "/" + connectionId,
                "GET", null, connectWaitTimeout);
        log.debug("Registered with app: status {}", response.statusCode());
        return response.statusCode();
    }
}
