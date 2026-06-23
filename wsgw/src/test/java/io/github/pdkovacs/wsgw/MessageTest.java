package io.github.pdkovacs.wsgw;

import jakarta.websocket.Session;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import io.github.pdkovacs.wsgw.logging.CtxLogger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

public class MessageTest {

    private static final CtxLogger logger = CtxLogger.of(MessageTest.class);

    final WsgwTestContext wsgwTestContext = new WsgwTestContext();

    @BeforeEach
    public void setUp(@TempDir Path tempDir) throws Exception {
        wsgwTestContext.setUp(tempDir);
    }

    @AfterEach
    public void tearDown() throws Exception {
        wsgwTestContext.tearDown();
    }

    @Test
    @Timeout(3)
    void relaysAMessageToApp() throws Exception {
        URI wsgwWebscoketServerURI = URI.create(wsgwTestContext.wsgwBaseUrl.toString());
        String connId = this.wsgwTestContext.connectionIdGeneratorMock.roll();
        var wsTestClient = wsgwTestContext.wsTestClients.connect(wsgwWebscoketServerURI, wsgwTestContext.apiKey);

        final String messageToApp = clientSendsMessageToApp(wsTestClient, connId);

        assertMessageToApp(connId, messageToApp);
    }

    @Test
    @Timeout(3)
    void sendsAMessageToClient() throws Exception {
        try {
            URI wsgwWebscoketServerURI = URI.create(wsgwTestContext.wsgwBaseUrl.toString());
            String connId = this.wsgwTestContext.connectionIdGeneratorMock.roll();
            var wsTestClient = wsgwTestContext.wsTestClients.connect(wsgwWebscoketServerURI, wsgwTestContext.apiKey);

            final String messageFromApp = appSendsMessageToClient(connId);

            assertMessageToClient(wsTestClient, messageFromApp);
        } catch (Exception e) {
            logger.error("Exception occurred during sending a message", e);
            throw new RuntimeException(e);
        }
    }

    @Test
    @Timeout(3)
    void sendReceiveAMessageFromAppSingleClientOneOff() throws Exception {
        try {
            URI wsgwWebscoketServerURI = URI.create(wsgwTestContext.wsgwBaseUrl.toString());
            String connId = this.wsgwTestContext.connectionIdGeneratorMock.roll();
            var wsTestClient = wsgwTestContext.wsTestClients.connect(wsgwWebscoketServerURI, wsgwTestContext.apiKey);

            final String messageSentToApp = clientSendsMessageToApp(wsTestClient, connId);
            final String messageSentToClient = appSendsMessageToClient(connId);

            assertMessageToApp(connId, messageSentToApp);
            assertMessageToClient(wsTestClient, messageSentToClient);
        } catch (Exception e) {
            logger.error("Exception occurred during sending a message", e);
            throw new RuntimeException(e);
        }
    }

    @Test
    @Timeout(3)
    void sendReceiveMessagesFromAppSingleClient() throws Exception {
        int nrMessagesToSend = 1000;

        URI wsgwWebscoketServerURI = URI.create(wsgwTestContext.wsgwBaseUrl.toString());
        String connId = this.wsgwTestContext.connectionIdGeneratorMock.roll();
        var wsTestClient = wsgwTestContext.wsTestClients.connect(wsgwWebscoketServerURI, wsgwTestContext.apiKey);

        final Collection<String> messagesSentToApp = new ConcurrentLinkedQueue<>();
        final Collection<String> messagesSentToClient = new ConcurrentLinkedQueue<>();

        var firstError = new AtomicReference<Throwable>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final Consumer<Callable<Boolean>> sendMessage = callable -> {
                try {
                    callable.call();
                } catch (Throwable tbl) {
                    if (firstError.compareAndSet(null, tbl)) {
                        executor.shutdownNow(); // interrupt the in-flight siblings
                    }
                }
            };

            for (int i = 0; i < nrMessagesToSend; i++) {
                executor.submit(() -> messagesSentToClient.add(appSendsMessageToClient(connId)));
            }

            // We're sending the messages to the application over the client websocket in (blocking) sequence in one
            // single task, because we would have to synchronize the individual calls on the remote client instance
            // anyway:
            executor.submit(() -> {
                for (int i = 0; i < nrMessagesToSend; i++) {
                    sendMessage.accept(() -> messagesSentToApp.add(clientSendsMessageToApp(wsTestClient, connId)));
                }
            });
        }
        var err = firstError.get();
        if (err != null) {
            throw new AssertionError("send failed under load", err);
        }
        wsTestClient.websocketClientSession().close();

        assertMessagesToApp(connId, nrMessagesToSend, List.copyOf(messagesSentToApp));
        assertMessagesToClient(wsTestClient, nrMessagesToSend, List.copyOf(messagesSentToClient));
    }

    private static @NonNull String clientSendsMessageToApp(WebsocketTestClient wsTestClient, String connId)
            throws IOException {
        final String messageToApp = "%s from client over %s".formatted(Math.random(), connId);
        Session session = wsTestClient.websocketClientSession();
        session.getBasicRemote().sendText(messageToApp);
        logger.debug("Message sent to app over {}: {}", connId, messageToApp);
        return messageToApp;
    }

    private void assertMessageToApp(String connId, String messageToApp) throws InterruptedException {
        Message msgReceivedByApp = wsgwTestContext.getAppInbox(connId).take();
        String text = switch (msgReceivedByApp) {
            case Message.Text(String t) -> t;
            case Message.EndOfStream _ ->
                fail("Expected message %s, got EndOfStream".formatted(messageToApp));
        };
        assertThat(text).isEqualTo(messageToApp);
    }

    private void assertMessagesToApp(String connId, int numberOfMessages, List<String> messagesSentToApp) throws InterruptedException {
        assertThat(messagesSentToApp.size()).isEqualTo(numberOfMessages);
        Set<String> received = new HashSet<>();
        var inboxQueue = wsgwTestContext.getAppInbox(connId);
        while (inboxQueue.take() instanceof Message.Text(String text)) {
            received.add(text);
        }
        assertThat(received).isEqualTo(new HashSet<>(messagesSentToApp));
    }

    private @NonNull String appSendsMessageToClient(String connId) throws IOException, InterruptedException {
        final String messageFromApp = "%s from app over %s".formatted(Math.random(), connId);
        logger.debug("Assembling request...");
        var msgToClientUrl = wsgwTestContext.getWsgwUrl("http", "/%s/%s".formatted(WsgwPaths.MESSAGE_FROM_APP, connId));
        HttpRequest request = HttpRequest.newBuilder(msgToClientUrl)
                .POST(HttpRequest.BodyPublishers.ofString(messageFromApp))
                .build();
        logger.debug("Request assembled");
        wsgwTestContext.httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        logger.debug("Request sent");
        return messageFromApp;
    }

    private static void assertMessageToClient(WebsocketTestClient wsTestClient, String messageFromApp)
            throws InterruptedException {
        var msgReceivedByClient = wsTestClient.messageInbox().take();
        String text = switch (msgReceivedByClient) {
            case Message.Text(String t) -> t;
            case Message.EndOfStream _ ->
                fail("Expected message %s, got EndOfStream".formatted(msgReceivedByClient));
        };
        assertThat(text).isEqualTo(messageFromApp);
    }

    private void assertMessagesToClient(WebsocketTestClient wsTestClient, int numberOfMessages, List<String> messagesSentToClient)
            throws InterruptedException {
        assertThat(messagesSentToClient.size()).isEqualTo(numberOfMessages);
        Set<String> received = new HashSet<>();
        var inboxQueue = wsTestClient.messageInbox();
        while (inboxQueue.take() instanceof Message.Text(String text)) {
            received.add(text);
        }
        assertThat(received).isEqualTo(new HashSet<String>(messagesSentToClient));
    }
}
