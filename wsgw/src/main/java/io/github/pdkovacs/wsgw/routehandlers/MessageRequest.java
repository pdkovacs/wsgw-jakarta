package io.github.pdkovacs.wsgw.routehandlers;

import io.github.pdkovacs.wsgw.*;
import io.github.pdkovacs.wsgw.backpressure.ConnectionGone;
import io.github.pdkovacs.wsgw.backpressure.SendWaitTimedOut;
import io.github.pdkovacs.wsgw.clientward.MessagePusher;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.stream.Collectors;

public class MessageRequest extends HttpFilter {

    private static final CtxLogger logger = CtxLogger.of(MessageRequest.class);

    private final MessagePusher messagePusher;

    public MessageRequest(MessagePusher messagePusher) {
        this.messagePusher = messagePusher;
    }

    @Override
    protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException {
        if (!req.getServletPath().startsWith(WsgwPaths.MESSAGE_FROM_APP)) {
            logger.error(getFilterName(), "Wrong path: %s".formatted(req.getServletPath()));
            res.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }

        var connectionId = ConnectionIdExtractor.extract(req.getServletPath(), 1);
        var log = logger.with("connId", connectionId);
        try {
            log.debug("Pushing message to client");
            var message = req.getReader().lines().collect(Collectors.joining(System.lineSeparator()));
            messagePusher.push(connectionId, message);
            log.debug("Message pushed to client");
        } catch (ConnectionGone sendBackpressure) {
            res.sendError(410 /*Gone*/, "Connection gone");
        } catch (SendWaitTimedOut sendBackpressure) {
            res.sendError(429 /*Too Many Requests*/, "Retry later");
        } catch (Exception e) {
            log.warn("Failed to push message to client", e);
            res.sendError(HttpServletResponse.SC_BAD_GATEWAY, "failed to reach application");
        }
    }
}
