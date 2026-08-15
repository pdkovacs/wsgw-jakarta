package io.github.pdkovacs.wsgw.socket;

import io.github.pdkovacs.wsgw.appward.Relay;
import io.github.pdkovacs.wsgw.appward.Relays;
import io.github.pdkovacs.wsgw.clientward.SessionRegistrar;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import jakarta.websocket.CloseReason;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;

import java.util.List;
import java.util.Map;

public class Endpoint extends jakarta.websocket.Endpoint {

    private static final CtxLogger logger = CtxLogger.of(Endpoint.class);

    private final SessionRegistrar sessionRegistrar;
    private final Relays appwardRelays; // ← constructor-injected (app scope)

    public Endpoint(SessionRegistrar sessionRegistrar, Relays appwardRelays) {
        this.sessionRegistrar = sessionRegistrar;
        this.appwardRelays = appwardRelays;
    }

    @Override
    public void onOpen(Session session, EndpointConfig config) {
        logger.debug("onOpen called");

        // per-connection wiring — read once, hydrate typed locals
        var connectionId = (String) config.getUserProperties().get("connectionId");
        // a connection-scoped logger; every line below carries connId as a field
        var log = logger.with("connId", connectionId);
        log.debug("connection opened");

        if (!sessionRegistrar.register(connectionId, session)) {
            log.debug("session abandoned");
            return;
        }

        @SuppressWarnings("unchecked")
        var connectHeaders = (Map<String, List<String>>) config.getUserProperties().get("connectHeaders");

        Relay relay = appwardRelays.createRelay(connectHeaders, connectionId);

        session.addMessageHandler(String.class, msg -> relay.sendMessage(msg));
        log.debug("onOpen completed");
    }

    @Override
    public void onClose(Session s, CloseReason r) {
        var mLogger = logger.with("method", "onClose");
        mLogger.debug("onClose called");

        var connectionId = (String) s.getUserProperties().get("connectionId");
        mLogger = mLogger.with("connectionId", connectionId);

        try {
            logger.debug("Websocket %s disconnected. Reason: %s".formatted(connectionId, r));
            var relay = appwardRelays.detachRelay(connectionId);
            if (relay != null) {
                relay.sendDisconnect();
            } else {
                mLogger.warn("Relay for connection not found: {}", connectionId);
            }
        } catch (Exception e) {
            logger.error("Error while disconnecting", e);
        }
    }
}