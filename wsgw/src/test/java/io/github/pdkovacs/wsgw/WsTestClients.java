package io.github.pdkovacs.wsgw;

import jakarta.websocket.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pdkovacs.wsgw.logging.CtxLogger;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

record WebsocketTestClient(TestClientEndpoint testClientEndpoint, Session websocketClientSession,
        String connectionId, BlockingQueue<Message> messageInbox) implements AutoCloseable {

    private static final CtxLogger logger =CtxLogger.of(WebsocketTestClient.class);

    public void close() throws Exception {
        // `close` defaults to new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE,
        // "no reason")
        logger.debug("Closing session...");
        websocketClientSession.close();
    }
}

class WsTestClients implements AutoCloseable {
    final private static Logger logger = LoggerFactory.getLogger(WsTestClients.class);

    private final List<WebsocketTestClient> clients = new ArrayList<>();

    WebsocketTestClient connect(URI uri, String[] apiKey) throws Exception {
        URI connectURI = URI.create(uri.toString().concat(WsgwPaths.CONNECT_FROM_CLIENT));
        var client = createConnectWebsocketClient(connectURI, apiKey);
        clients.add(client);
        return client;
    }

    @Override
    public void close() {
        logger.debug("Closing all connections...");
        for (var c : clients) {
            try {
                c.close();
            } catch (Exception e) {
                logger.warn("client close failed", e);
            }
        }
        clients.clear();
    }

    private WebsocketTestClient createConnectWebsocketClient(
            URI wsgwConnectUri,
            String[] apiKey) throws Exception {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer(); // ← Tomcat's client impl
        var messageInbox = new LinkedBlockingQueue<Message>();
        TestClientEndpoint testClientEndpoint = new TestClientEndpoint(messageInbox);
        // To exercise the auth path in tests, attach handshake headers with a client
        // Configurator:
        var futureConnectionId = new CompletableFuture<String>();
        var cfg = ClientEndpointConfig.Builder.create()
                .configurator(new ClientEndpointConfig.Configurator() {
                    @Override
                    public void beforeRequest(Map<String, List<String>> headers) {
                        headers.put(apiKey[0], List.of(apiKey[1])); // app checks header[name]==value
                    }

                    @Override
                    public void afterResponse(HandshakeResponse hr) {
                        var connectionId = hr.getHeaders().get(WsgwPaths.CONNECTION_ID_HEADER_KEY).getFirst();
                        futureConnectionId.complete(connectionId);
                        logger.debug("ClientEndpointConfig.Configurator after handshake done");
                    }
                }).build();
        Session session = container.connectToServer(testClientEndpoint, cfg, wsgwConnectUri);
        // connectToServer returns only after onOpen has run, so the session is ready
        // here — no latch needed.
        var wsTestClient = new WebsocketTestClient(testClientEndpoint, session, futureConnectionId.get(), messageInbox);
        clients.add(wsTestClient);
        return wsTestClient;
    }
}

class TestClientEndpoint extends Endpoint {
    private static final CtxLogger logger = CtxLogger.of(TestClientEndpoint.class);
    final BlockingQueue<Message> messageInbox;

    Session session;

    public TestClientEndpoint(BlockingQueue<Message> messageInbox) {
        this.messageInbox = messageInbox;
    }

    @Override
    public void onOpen(Session session, EndpointConfig endpointConfig) {
        this.session = session;
        this.session.addMessageHandler(new MessageHandler.Whole<String>() {
            @Override
            public void onMessage(String message) {
                try {
                    logger.debug("Message received and added to inbox: {}", message);
                    messageInbox.put(new Message.Text(message));
                } catch (InterruptedException e) {
                    logger.error("Interrupted while putting message to inbox", e);
                }
            }
        });
    }

    @Override
    public void onClose(Session session, CloseReason closeReason) {
        // Signal stream-end so a blocked messageInbox().take() unparks instead of
        // hanging until the test's @Timeout fires.
        try {
            logger.debug("Closing session {}...", session.getId());
            messageInbox.put(Message.EndOfStream.INSTANCE);
        } catch (InterruptedException e) {
            logger.error("Interrupted while putting EndOfStream to inbox", e);
        }
    }
}
