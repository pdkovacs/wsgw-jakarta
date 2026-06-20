package io.github.pdkovacs.wsgw.filters;

import io.github.pdkovacs.wsgw.*;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.stream.Collectors;

public class FromAppMessage extends HttpFilter {

    private static final CtxLogger log = CtxLogger.of(FromAppMessage.class);

    private final MessagePusher messagePusher;

    public FromAppMessage(MessagePusher messagePusher) {
        this.messagePusher = messagePusher;
    }

    @Override
    protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        var connectionId = ConnectionIdExtractor.extract(req.getServletPath(), 1);
        var log = FromAppMessage.log.with("connId", connectionId);
        try {
            var message = req.getReader().lines().collect(Collectors.joining(System.lineSeparator()));
            messagePusher.sendTo(connectionId, message);   // blocking; cheap on a virtual thread
            log.debug("Message pushed to client");
        } catch (Exception e) {
            log.warn("Failed to push message to client", e);
            res.sendError(HttpServletResponse.SC_BAD_GATEWAY, "failed to reach application");
        }
    }
}
