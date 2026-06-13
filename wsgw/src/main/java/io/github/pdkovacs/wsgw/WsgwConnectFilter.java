package io.github.pdkovacs.wsgw;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class WsgwConnectFilter extends HttpFilter {

    private static final Logger log = LoggerFactory.getLogger(WsgwConnectFilter.class);

    private final String appBaseUrl;
    private final ConnectionIdProvider connectionIdProvider;

    public WsgwConnectFilter(String appBaseUrl, ConnectionIdProvider connectionIdProvider) {
        this.appBaseUrl = appBaseUrl;
        this.connectionIdProvider = connectionIdProvider;
    }

    @Override
    protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        var path = req.getServletPath();
        var reqHeaders = RequestToApp.getRequestHeaders(req);

        var connectionId = this.connectionIdProvider.generateId();
        int appStatus;
        try {
            appStatus = registerWithApp(reqHeaders, connectionId);   // blocking; cheap on a virtual thread
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

    // Relays the client's connection request to the backend's /connect endpoint
    // and returns the HTTP status the backend answered with. 204 means accepted.
    // The response body is discarded -- only the status code matters here.
    private int registerWithApp(Map<String, List<String>> reqHeaders, String connectionId) throws Exception {
        log.debug("About to register {} with app at {}", connectionId, appBaseUrl);
        HttpResponse<Void> response = RequestToApp.send(appBaseUrl, reqHeaders, connectionId, AppPaths.CONNECT_FROM_WSGW, "GET", null);
        log.debug("Registered {} with app at {}: status {}", connectionId, appBaseUrl, response.statusCode());
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
        return RequestToApp.CONN_ID_HEADER.equalsIgnoreCase(name)
                ? connectionId
                : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        return RequestToApp.CONN_ID_HEADER.equalsIgnoreCase(name)
                ? Collections.enumeration(List.of(connectionId))
                : super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {   // ← without this, the id is invisible to modifyHandshake
        var names = new ArrayList<>(Collections.list(super.getHeaderNames()));
        names.add(RequestToApp.CONN_ID_HEADER);
        return Collections.enumeration(names);
    }
}
