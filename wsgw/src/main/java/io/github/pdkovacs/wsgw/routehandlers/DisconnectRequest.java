package io.github.pdkovacs.wsgw.routehandlers;

import io.github.pdkovacs.wsgw.*;
import io.github.pdkovacs.wsgw.clientward.SessionCloser;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public class DisconnectRequest extends HttpFilter {

    private static final CtxLogger logger = CtxLogger.of(DisconnectRequest.class);

    private final SessionCloser sessionCloser;

    public DisconnectRequest(SessionCloser sessionCloser) {
        this.sessionCloser = sessionCloser;
    }

    @Override
    protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        logger.debug("/disconnect request {}");

        if (!req.getServletPath().startsWith(WsgwPaths.DISCONNECT_FROM_APP)) {
            res.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }

        var connectionId = ConnectionIdExtractor.extract(req.getServletPath(), 1);
        var log = logger.with("connId", connectionId);
        try {
            sessionCloser.close(connectionId);
        } catch (Exception e) {
            log.warn("Failed to disconnect client", e);
            res.sendError(HttpServletResponse.SC_BAD_GATEWAY, "failed to reach application");
        }
    }
}
