package io.github.pdkovacs.wsgw;

import io.github.pdkovacs.wsgw.appside.ToApp;
import io.github.pdkovacs.wsgw.clientside.SessionRegistrar;
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
    private final ToApp relay; // ← constructor-injected (app scope)

    public WsgwEndpoint(SessionRegistrar registerSession, ToApp relay) {
        this.registerSession = registerSession;
        this.relay = relay;
    }

    @Override
    public void onOpen(Session session, EndpointConfig config) {
        logger.debug("onOpen called");

        // per-connection wiring — read once, hydrate typed locals
        var connectionId = (String) config.getUserProperties().get("connectionId");
        // a connection-scoped logger; every line below carries connId as a field
        var log = logger.with("connId", connectionId);
        log.debug("connection opened");

        registerSession.register(connectionId, session);

        @SuppressWarnings("unchecked")
        var connectHeaders = (Map<String, List<String>>) config.getUserProperties().get("connectHeaders");

        session.addMessageHandler(String.class, msg -> relay.sendMessage(connectHeaders, connectionId, msg));
        log.debug("onOpen completed");
    }

    @Override
    public void onClose(Session s, CloseReason r) {
        // The text at lines 177–180 is the normative contract in Jakarta WebSocket 2.1
        // bundled with Tomcat 11:
        // The user properties made available via
        // ServerEndpointConfig#getUserProperties() must be a per WebSocket connection
        // (i.e. per Session) copy of the user properties. This copy, including any
        // modifications made to the user properties during the execution of this method
        // must be used to populate the initial contents of Session#getUserProperties().
        try {
            var connectionId = (String) s.getUserProperties().get("connectionId");
            @SuppressWarnings("unchecked")
            var connectHeaders = (Map<String, List<String>>) s.getUserProperties().get("connectHeaders");

            logger.debug("Websocket %s disconnected. Reason: %s".formatted(connectionId, r));
            relay.sendDisconnect(connectHeaders, connectionId);
        } catch (Exception e) {
            logger.error("Error while disconnecting", e);
        }
    }
}