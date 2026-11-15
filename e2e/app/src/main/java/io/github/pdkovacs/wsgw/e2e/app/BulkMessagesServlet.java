package io.github.pdkovacs.wsgw.e2e.app;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pdkovacs.wsgw.e2e.app.service.MessageSender;
import io.github.pdkovacs.wsgw.e2e.common.dto.E2EMessage;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

public class BulkMessagesServlet extends HttpServlet {

    public static final String PATH = "/api/messages-in-bulk";

    private static final CtxLogger logger = CtxLogger.of(BulkMessagesServlet.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<E2EMessage>> MESSAGE_LIST = new TypeReference<>() {
    };

    private final MessageSender messageSender;

    public BulkMessagesServlet(MessageSender messageSender) {
        this.messageSender = messageSender;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        final List<E2EMessage> messages;
        try {
            messages = MAPPER.readValue(req.getInputStream(), MESSAGE_LIST);
        } catch (JacksonException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "body should be a JSON array of messages");
            return;
        }
        if (messages == null) { // a literal "null" body
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "body should be a JSON array of messages");
            return;
        }

        try {
            messageSender.sendAll(messages);
            resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
        } catch (Exception e) {
            logger.error("Exception in BulkMessagesServlet", e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }
}
