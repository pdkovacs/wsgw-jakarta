package io.github.pdkovacs.wsgw.filters;

import io.github.pdkovacs.wsgw.*;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.stream.Collectors;

public class PushToClient extends HttpFilter {

    private static final Logger log = LoggerFactory.getLogger(PushToClient.class);

    private final MessagePusher messagePusher;

    public PushToClient(MessagePusher messagePusher) {
        this.messagePusher = messagePusher;
    }

    @Override
    protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        try {
            var connectionId = ConnectionIdExtractor.extract(req.getServletPath(), 1);
            var message = req.getReader().lines().collect(Collectors.joining(System.lineSeparator()));
            messagePusher.sendTo(connectionId, message);   // blocking; cheap on a virtual thread
        } catch (Exception e) {
            res.sendError(HttpServletResponse.SC_BAD_GATEWAY, "failed to reach application");
            return;
        }
    }
}
