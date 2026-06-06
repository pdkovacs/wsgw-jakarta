package io.github.pdkovacs.wsgw;

import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;

import java.util.List;

public class ConnIdConfigurator extends ServerEndpointConfig.Configurator {
    @Override
    public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest req, HandshakeResponse res) {
        var id = req.getHeaders().getOrDefault(WsgwAuthFilter.CONN_ID_HEADER, List.of("?")).get(0);
        sec.getUserProperties().put("connectionId", id);  // copied into Session.getUserProperties()
    }
}
