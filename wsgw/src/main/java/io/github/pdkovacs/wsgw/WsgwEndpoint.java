package io.github.pdkovacs.wsgw;

import io.github.pdkovacs.wsgw.logging.CtxLogger;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;

import java.util.List;
import java.util.Map;

public class WsgwEndpoint extends Endpoint {

    private static final CtxLogger logger = CtxLogger.of(WsgwEndpoint.class);

    private final SessionRegistrar registerSession;
    private final MessageRelay relay;                 // ← constructor-injected (app scope)

    public WsgwEndpoint(SessionRegistrar registerSession, MessageRelay relay) {
        this.registerSession = registerSession;
        this.relay = relay;
    }

    @Override
    public void onOpen(Session session, EndpointConfig config) {
        logger.debug("onOpen called");

        // per-connection wiring — read once, hydrate typed locals
        var connectionId  = (String) config.getUserProperties().get("connectionId");
        // a connection-scoped logger; every line below carries connId as a field
        var log = logger.with("connId", connectionId);
        log.debug("connection opened");

        registerSession.register(connectionId, session);

        @SuppressWarnings("unchecked")
        var connectHeaders = (Map<String, List<String>>) config.getUserProperties().get("connectHeaders");

        session.addMessageHandler(String.class, msg -> relay.relay(connectHeaders, connectionId, msg));   // ← both scopes meet in the closure
        log.debug("onOpen completed");
    }

    @Override public void onClose(Session s, CloseReason r) { /* ... */ }
}