package io.github.pdkovacs.wsgw;

import jakarta.websocket.CloseReason;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WsgwEndpoint extends Endpoint {

    private static final Logger log = LoggerFactory.getLogger(WsgwEndpoint.class.getName());

    @Override
    public void onOpen(Session session, EndpointConfig config) {
        var connectionId = (String) session.getUserProperties().get("connectionId");
        session.addMessageHandler(String.class, msg -> {
            log.debug("WS connection {} text: {}", connectionId, msg);
        });
        log.debug("WS connection {} opened", connectionId);
    }

    @Override public void onClose(Session s, CloseReason r) { /* ... */ }
}
