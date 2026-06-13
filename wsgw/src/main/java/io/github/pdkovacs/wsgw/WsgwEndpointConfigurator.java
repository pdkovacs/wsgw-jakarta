package io.github.pdkovacs.wsgw;

import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class WsgwEndpointConfigurator extends ServerEndpointConfig.Configurator {

    private static final Logger logger = LoggerFactory.getLogger(WsgwEndpointConfigurator.class);

    private final MessageRelay messageRelay;

    public WsgwEndpointConfigurator(MessageRelay messageRelay) {
        this.messageRelay = messageRelay;
    }


    @Override
    public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest req, HandshakeResponse res) {
        logger.debug("modifyHandshake called");
        var id = req.getHeaders().getOrDefault(RequestToApp.CONN_ID_HEADER, List.of("?")).getFirst();
        sec.getUserProperties().put("connectionId", id);
        sec.getUserProperties().put("connectHeaders", req.getHeaders());
        res.getHeaders().put(RequestToApp.CONN_ID_HEADER, List.of(id));  // echo on the 101 response
        logger.debug("modifyHandshake completed");
    }

    @Override
    public <T> T getEndpointInstance(Class<T> clazz) {
        logger.debug("getEndpointInstance called");
        if (clazz != WsgwEndpoint.class) {
            logger.error("Unexpected endpoint type: " + clazz);
            // WsgwEndpoint is the only endpoint currently implemented
            throw new IllegalArgumentException("Unexpected endpoint type: " + clazz);
        }
        T endpointInstance = clazz.cast(new WsgwEndpoint(messageRelay));
        logger.debug("getEndpointInstance completed");
        return endpointInstance;
    }
}
