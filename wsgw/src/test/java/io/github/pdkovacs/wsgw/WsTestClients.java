package io.github.pdkovacs.wsgw;

import jakarta.websocket.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

record WebsocketTestClient(TestClientEndpoint testClientEndpoint, Session websocketClientSession,
                           String connectionId) implements AutoCloseable {

    public void close() throws Exception {
        // `close` defaults to new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "no reason")
        websocketClientSession.close();
    }
}

class WsTestClients implements AutoCloseable {
    final private static Logger log = LoggerFactory.getLogger(WsTestClients.class);

    private final List<WebsocketTestClient> clients = new ArrayList<>();

    WebsocketTestClient connect(URI uri, String[] apiKey) throws Exception {
        URI connectURI = URI.create(uri.toString().concat(WsgwPaths.CONNECT_FROM_CLIENT));
        var client = createConnectWebsocketClient(connectURI, apiKey);
        clients.add(client);
        return client;
    }

    @Override
    public void close() {
        for (var c : clients) {
            try {
                c.close();
            } catch (Exception e) {
                log.warn("client close failed", e);
            }
        }
        clients.clear();
    }

    private WebsocketTestClient createConnectWebsocketClient(
            URI wsgwConnectUri,
            String[] apiKey
    ) throws Exception {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();   // ← Tomcat's client impl
        TestClientEndpoint testClientEndpoint = new TestClientEndpoint();
        // To exercise the auth path in tests, attach handshake headers with a client Configurator:
        var futureConnectionId = new CompletableFuture<String>();
        var cfg = ClientEndpointConfig.Builder.create()
                .configurator(new ClientEndpointConfig.Configurator() {
                    @Override
                    public void beforeRequest(Map<String, List<String>> headers) {
                        headers.put(apiKey[0], List.of(apiKey[1]));   // app checks header[name]==value
                    }

                    @Override
                    public void afterResponse(HandshakeResponse hr) {
                        var connectionId = hr.getHeaders().get("X-WSGW-CONNECTION-ID").getFirst();
                        futureConnectionId.complete(connectionId);
                        log.debug("ClientEndpointConfig.Configurator after handshake done");
                    }
                }).build();
        Session session = container.connectToServer(testClientEndpoint, cfg, wsgwConnectUri);
        // connectToServer returns only after onOpen has run, so the session is ready here — no latch needed.
        var wsTestClient = new WebsocketTestClient(testClientEndpoint, session, futureConnectionId.get());
        clients.add(wsTestClient);
        return wsTestClient;
    }
}

class TestClientEndpoint extends Endpoint {
    final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

    Session session;

    @Override
    public void onOpen(Session session, EndpointConfig endpointConfig) {
        this.session = session;
        this.session.addMessageHandler(new MessageHandler.Whole<String>() {
            @Override
            public void onMessage(String message) {
                messages.add(message);
            }
        });
    }
}
