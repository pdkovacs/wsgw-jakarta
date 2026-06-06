package io.github.pdkovacs.wsgw;

import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

public class AppTest {
    @Test
    public void testClientEndpoint() {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();   // ← Tomcat's client impl
        TestClient client = new TestClient();
        Session session = container.connectToServer(client, URI.create("ws://localhost:" + port + "/ws"));

        assertThat(session.isOpen()).isTrue();   // same assertion you have selected at AppTest.java:155

        // To exercise the auth path in tests, attach handshake headers with a client Configurator:
        var cfg = ClientEndpointConfig.Builder.create()
                .configurator(new ClientEndpointConfig.Configurator() {
                    @Override public void beforeRequest(Map<String, List<String>> headers) {
                        headers.put("Authorization", List.of("Basic " + creds));
                    }
                }).build();
        Session s = container.connectToServer(client, cfg, uri);
    }
}
