package io.github.pdkovacs.wsgw.integration;

import io.github.pdkovacs.wsgw.WsgwPaths;
import io.github.pdkovacs.wsgw.appward.Request;
import jakarta.websocket.*;
import org.assertj.core.api.Assertions;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pdkovacs.wsgw.logging.CtxLogger;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

record WebsocketTestClient(String wsgwServer, HttpClient httpClient,
                           TestClientEndpoint testClientEndpoint, Session websocketClientSession,
                           String connectionId, BlockingQueue<Message> messageInbox) implements AutoCloseable {
    private static final CtxLogger logger = CtxLogger.of(WebsocketTestClient.class);

    public static WebsocketTestClient of(String wsgwServer, TestClientEndpoint testClientEndpoint,
                                         Session websocketClientSession, String connectionId,
                                         BlockingQueue<Message> messageInbox) {
        return new WebsocketTestClient(wsgwServer, Request.createHttpClient(HttpClient.Version.HTTP_2),
                testClientEndpoint, websocketClientSession, connectionId, messageInbox);
    }

    public @NonNull String postMessageFromApp()
            throws IOException, InterruptedException, URISyntaxException {
        var tcLoggerr = logger.with("method", "postMessageFromApp").with("connId", connectionId);
        final String messageFromApp = "%s from app over %s".formatted(Math.random(), connectionId());
        String msgToClientPath = "/%s/%s".formatted(WsgwPaths.MESSAGE_FROM_APP, connectionId);
        String msgToClientUrl = "http://%s%s".formatted(wsgwServer, msgToClientPath);
        HttpRequest request = HttpRequest.newBuilder(new URI(msgToClientUrl))
                .POST(HttpRequest.BodyPublishers.ofString(messageFromApp))
//                .timeout(Duration.ofSeconds(1))
                .build();
        tcLoggerr.debug("Sending request...");
        var response = httpClient().send(request, HttpResponse.BodyHandlers.discarding());
        Assertions.assertThat(response.version()).isEqualTo(HttpClient.Version.HTTP_2);
        tcLoggerr.debug("Request sent");
        return messageFromApp;
    }

    public void close() throws Exception {
        // `close` defaults to new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE,
        // "no reason")
        logger.debug("Closing session...");
        websocketClientSession.close();
    }
}

class WsTestClients implements AutoCloseable {
    final private static Logger logger = LoggerFactory.getLogger(WsTestClients.class);

    private final List<WebsocketTestClient> clients = Collections.synchronizedList(new ArrayList<>());

    WebsocketTestClient connect(String wsgwServerName, String[] apiKey) throws Exception {
        // createConnectWebsocketClient already registers the client for teardown, so don't add twice.
        return createConnectWebsocketClient(wsgwServerName, apiKey);
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
            String wsgwServerName,
            String[] apiKey) throws Exception {
        URI connectURI = URI.create("ws://%s".formatted(wsgwServerName.concat(WsgwPaths.CONNECT_FROM_CLIENT)));
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
        // Increase the client handshake timeout, to increase the patience of the client to wait for the Upgrade
        // request to return a meaningful result:
        // cfg.getUserProperties().put("org.apache.tomcat.websocket.IO_TIMEOUT_MS", "30000");

        Session session = container.connectToServer(testClientEndpoint, cfg, connectURI);
        // connectToServer returns only after onOpen has run, so the session is ready
        // here — no latch needed.
        var wsTestClient = WebsocketTestClient.of(wsgwServerName, testClientEndpoint, session, futureConnectionId.get(), messageInbox);
        // Send a warm-up request to prime the h2c connection as long as no-concurrency is guaranteed,
        // because concurrent requests sporadically fall back to HTTP/1.1 under stress
        wsTestClient.postMessageFromApp();
        wsTestClient.messageInbox().take();

        clients.add(wsTestClient);
        return wsTestClient;
    }

    public List<WebsocketTestClient> getClients() {
        return clients;
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
            logger.debug("Closing session {} with reason {}...", session.getId(), closeReason);
            messageInbox.put(Message.EndOfStream.INSTANCE);
        } catch (InterruptedException e) {
            logger.error("Interrupted while putting EndOfStream to inbox", e);
        }
    }
}
