package io.github.pdkovacs.wsgw;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

public class WsgwConnectFilter extends HttpFilter {
    // Hop-by-hop WebSocket upgrade headers (plus host/content-length, which the
    // java.net.http client manages itself). These are stripped before relaying
    // the client's headers to the backend, since the WS upgrade happens between
    // the client and wsgw, not on the wsgw->backend leg.
    private static final Set<String> RESTRICTED_HEADERS = Set.of(
            "host",
            "upgrade",
            "connection",
            "content-length",
            "sec-websocket-key",
            "sec-websocket-version",
            "sec-websocket-extensions",
            "sec-websocket-protocol");

    private static final Logger log = LoggerFactory.getLogger(WsgwConnectFilter.class);

    static final String CONN_ID_HEADER = "X-WSGW-CONNECTION-ID";

    private final String appBaseUrl;
    private final HttpClient appClient;   // same java.net.http client as the Jetty version
    private final ConnectionIdProvider connectionIdProvider;

    public WsgwConnectFilter(String appBaseUrl, HttpClient appClient, ConnectionIdProvider connectionIdProvider) {
        this.appBaseUrl = appBaseUrl;
        this.appClient = appClient;
        this.connectionIdProvider = connectionIdProvider;
    }

    @Override
    protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        var path = req.getServletPath();

        log.debug("doFilter: path = {}", path);

        if (!path.equals(WsgwPaths.CONNECT_FROM_CLIENT)) {
            log.debug("HttpHandler minding its own business...");
            res.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        var connectionId = this.connectionIdProvider.generateId();
        int appStatus;
        try {
            appStatus = registerWithApp(req, connectionId);   // blocking; cheap on a virtual thread
        } catch (Exception e) {
            res.sendError(HttpServletResponse.SC_BAD_GATEWAY, "failed to reach application");
            return;
        }

        if (appStatus != HttpServletResponse.SC_NO_CONTENT) {
            if (appStatus == HttpServletResponse.SC_UNAUTHORIZED ) {
                res.setHeader("WWW-Authenticate", "Basic realm=\"wsgw\"");  // same RFC-7235 dance
                res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            } else {
                res.sendError(HttpServletResponse.SC_BAD_GATEWAY);
            }
            return;   // client never sees a WS handshake — parity with Go wsgw
        }

        // Accepted: hand the id to the endpoint by injecting a header the
        // Configurator can read during the handshake, then let the upgrade proceed.
        chain.doFilter(new ConnIdRequestWrapper(req, connectionId), res);
    }

    private static boolean isRestricted(String headerName) {
        return RESTRICTED_HEADERS.contains(headerName.toLowerCase(Locale.ROOT));
    }

    // Relays the client's connection request to the backend's /connect endpoint
    // and returns the HTTP status the backend answered with. 204 means accepted.
    // The response body is discarded -- only the status code matters here.
    private int registerWithApp(HttpServletRequest req, String connectionId) throws Exception {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(appBaseUrl + AppPaths.CONNECT_FROM_WSGW));
        requestBuilder.setHeader(Wsgw.X_WSGW_CONNECTION_ID, connectionId);

        var headerNames = Collections.list(req.getHeaderNames());
        for (String headerName : headerNames) {
            if (isRestricted(headerName)) {
                continue;
            }
            // header() (vs setHeader()) preserves multi-valued headers.
            requestBuilder.header(headerName, req.getHeader(headerName));
        }

        log.debug("Registering with app {}: trying...", appBaseUrl);
        HttpResponse<Void> response = appClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.discarding());
        log.debug("Registered with app {}: status {}", appBaseUrl, response.statusCode());
        return response.statusCode();
    }
}

class ConnIdRequestWrapper extends HttpServletRequestWrapper {
    private final String connectionId;

    ConnIdRequestWrapper(HttpServletRequest request, String connectionId) {
        super(request);
        this.connectionId = connectionId;
    }

    @Override
    public String getHeader(String name) {
        return WsgwConnectFilter.CONN_ID_HEADER.equalsIgnoreCase(name)
                ? connectionId
                : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        return WsgwConnectFilter.CONN_ID_HEADER.equalsIgnoreCase(name)
                ? Collections.enumeration(List.of(connectionId))
                : super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {   // ← without this, the id is invisible to modifyHandshake
        var names = new ArrayList<>(Collections.list(super.getHeaderNames()));
        names.add(WsgwConnectFilter.CONN_ID_HEADER);
        return Collections.enumeration(names);
    }
}
