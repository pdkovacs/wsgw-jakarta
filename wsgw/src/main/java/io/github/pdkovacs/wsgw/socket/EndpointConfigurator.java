package io.github.pdkovacs.wsgw.socket;

import io.github.pdkovacs.wsgw.WsgwPaths;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pdkovacs.wsgw.appward.Relay;
import io.github.pdkovacs.wsgw.clientward.SessionRegistrar;

import java.util.List;

public class EndpointConfigurator extends ServerEndpointConfig.Configurator {

    private static final Logger logger = LoggerFactory.getLogger(EndpointConfigurator.class);

    private final Relay appwardRelay;
    private final SessionRegistrar registerSession;

    public EndpointConfigurator(Relay appwardRelay, SessionRegistrar registerSession) {
        this.appwardRelay = appwardRelay;
        this.registerSession = registerSession;
    }

    @Override
    public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest req, HandshakeResponse res) {
        logger.debug("modifyHandshake called");
        var id = req.getHeaders().getOrDefault(WsgwPaths.CONNECTION_ID_HEADER_KEY, List.of("?")).getFirst();
        sec.getUserProperties().put("connectionId", id);
        sec.getUserProperties().put("connectHeaders", req.getHeaders());
        res.getHeaders().put(WsgwPaths.CONNECTION_ID_HEADER_KEY, List.of(id)); // echo on the 101 response
        logger.debug("modifyHandshake completed");
    }

    @Override
    public <T> T getEndpointInstance(Class<T> clazz) {
        logger.debug("getEndpointInstance called");
        if (clazz != Endpoint.class) {
            logger.error("Unexpected endpoint type: " + clazz);
            // Endpoint is the only endpoint currently implemented
            throw new IllegalArgumentException("Unexpected endpoint type: " + clazz);
        }
        T endpointInstance = clazz.cast(new Endpoint(registerSession, appwardRelay));
        logger.debug("getEndpointInstance completed");
        return endpointInstance;
    }
}
