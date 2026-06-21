package io.github.pdkovacs.wsgw.routehandlers;

import io.github.pdkovacs.wsgw.AppPaths;
import io.github.pdkovacs.wsgw.ConnectionIdProvider;
import io.github.pdkovacs.wsgw.WsgwPaths;
import io.github.pdkovacs.wsgw.appside.RequestToApp;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

public class ConnectionRequest extends HttpFilter {

    private static final CtxLogger log = CtxLogger.of(ConnectionRequest.class);

    private final String appBaseUrl;
    private final ConnectionIdProvider connectionIdProvider;

    public ConnectionRequest(String appBaseUrl, ConnectionIdProvider connectionIdProvider) {
        this.appBaseUrl = appBaseUrl;
        this.connectionIdProvider = connectionIdProvider;
    }

    @Override
    protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        String path = req.getServletPath();
        if (!path.startsWith(WsgwPaths.CONNECT_FROM_CLIENT)) {
            res.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }

        var reqHeaders = RequestToApp.getRequestHeaders(req);

        var connectionId = this.connectionIdProvider.generateId();
        int appStatus;
        try {
            appStatus = registerWithApp(reqHeaders, connectionId); // blocking; cheap on a virtual thread
        } catch (Exception e) {
            res.sendError(HttpServletResponse.SC_BAD_GATEWAY, "failed to reach application");
            return;
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
        var log = ConnectionRequest.log.with("connId", connectionId).with("appBaseUrl", appBaseUrl);
        log.debug("About to register with app");
        HttpResponse<Void> response = RequestToApp.send(appBaseUrl, reqHeaders,
                AppPaths.CONNECT_FROM_WSGW + "/" + connectionId, "GET", null);
        log.debug("Registered with app: status {}", response.statusCode());
        return response.statusCode();
    }
}
