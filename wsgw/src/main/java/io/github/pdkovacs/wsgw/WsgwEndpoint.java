package io.github.pdkovacs.wsgw;

import jakarta.websocket.CloseReason;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class WsgwEndpoint extends Endpoint {

    private static final Logger logger = LoggerFactory.getLogger(WsgwEndpoint.class.getName());

    private final MessageRelay relay;                 // ← constructor-injected (app scope)

    public WsgwEndpoint(MessageRelay relay) {
        this.relay = relay;
    }

    @Override
    public void onOpen(Session session, EndpointConfig config) {
        logger.debug("onOpen called");

        // per-connection wiring — read once, hydrate typed locals
        var connectionId  = (String) config.getUserProperties().get("connectionId");
        logger.debug("connectionId: " + connectionId);
        @SuppressWarnings("unchecked")
        var connectHeaders = (Map<String, List<String>>) config.getUserProperties().get("connectHeaders");

        session.addMessageHandler(String.class, msg -> relay.relay(connectHeaders, connectionId, msg));   // ← both scopes meet in the closure
        logger.debug("onOpen completed");
    }

    @Override public void onClose(Session s, CloseReason r) { /* ... */ }
}