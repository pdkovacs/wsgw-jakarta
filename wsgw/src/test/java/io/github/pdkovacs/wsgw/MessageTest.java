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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;

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

        final String messageToApp = sendMessageFromClientToApp(wsTestClient, connId);

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

            final String messageSentToApp = sendMessageFromClientToApp(wsTestClient, connId);
            final String messageSentToClient = sendMessageFromAppToClient(connId);

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
                executor.submit(() -> messagesSentToClient.add(sendMessageFromAppToClient(connId)));
            }

            // We're sending the messages to the application over the client websocket in (blocking) sequence in one
            // single task, because we would have to synchronize the individual calls on the remote client instance
            // anyway:
            executor.submit(() -> {
                for (int i = 0; i < nrMessagesToSend; i++) {
                    sendMessage.accept(() -> messagesSentToApp.add(sendMessageFromClientToApp(wsTestClient, connId)));
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

    @Test
    @Timeout(30)
    void sendReceiveMessagesFromAppMultipleClients() throws Exception {
        final var tcLogger = logger.with("method", "sendReceiveMessagesFromAppMultipleClients");

        int nrClients = 10;
        int nrMessagesToSend = 100;

        URI wsgwURI = URI.create(wsgwTestContext.wsgwBaseUrl.toString());
        var testClients = wsgwTestContext.wsTestClients;

        record ClientTestCtx(
            WebsocketTestClient testClient,
            Collection<String> messagesSentToApp,
            Collection<String> messagesSentToClient
        ) {};

        final Collection<ClientTestCtx> processedClientContexts = new ConcurrentLinkedQueue<>();

        var firstError = new AtomicReference<Throwable>();
        try (var topExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            final BlockingQueue<ClientTestCtx> clientContextsToStart = new LinkedBlockingQueue<>();;

            final Callable<Boolean> createWsConnection = () -> {
                WebsocketTestClient wsTestClient = testClients.connect(wsgwURI, wsgwTestContext.apiKey);
                final Collection<String> messagesSentToApp = new ConcurrentLinkedQueue<>();
                final Collection<String> messagesSentToClient = new ConcurrentLinkedQueue<>();
                clientContextsToStart.put(new ClientTestCtx(wsTestClient, messagesSentToApp, messagesSentToClient));
                return true;
            };

            final BiConsumer<ExecutorService, Callable<Boolean>> submitErrorChecked = (executor, callable) -> {
                executor.submit(() -> {
                    try {
                        callable.call();
                    } catch (Throwable tbl) {
                        tcLogger.error("Error", tbl);
                        if (firstError.compareAndSet(null, tbl)) {
                            executor.shutdownNow(); // interrupt the in-flight siblings
                        }
                    }
                });
            };

            final Supplier<Boolean> noImpeds = () -> firstError.get() == null && !Thread.currentThread().isInterrupted();

            final Function<ClientTestCtx, Boolean> sendMessages = clientTestCtx -> {
                try (var sendMessageExeuctor = Executors.newVirtualThreadPerTaskExecutor()) {
                    processedClientContexts.add(clientTestCtx);
                    var wsTestClient = clientTestCtx.testClient();
                    var connId = wsTestClient.connectionId();
                    var messagesSentToClient = clientTestCtx.messagesSentToClient();
                    for (int i = 0; i < nrMessagesToSend && noImpeds.get(); i++) {
                        submitErrorChecked.accept(sendMessageExeuctor, () -> messagesSentToClient.add(sendMessageFromAppToClient(connId)));
                    }

                    var messagesSentToApp = clientTestCtx.messagesSentToApp();
                    // We're sending the messages to the application over the client websocket in (blocking) sequence in one
                    // single task, because we would have to synchronize the individual calls on the remote client instance
                    // anyway:
                    submitErrorChecked.accept(sendMessageExeuctor, () -> {
                        for (int i = 0; i < nrMessagesToSend  && noImpeds.get(); i++) {
                            messagesSentToApp.add(sendMessageFromClientToApp(wsTestClient, connId));
                        }
                        return true;
                    });
                }
                return true;
            };

            for (int i = 0; i < nrClients && noImpeds.get(); i++) {
                submitErrorChecked.accept(topExecutor, () -> createWsConnection.call());
            }
            for (int i = 0; i < nrClients && noImpeds.get(); i++) {
                tcLogger.debug("Taking client context to start...");
                var clientCtx = clientContextsToStart.take();
                tcLogger.debug("Client context to start {}", clientCtx.testClient.connectionId());
                submitErrorChecked.accept(topExecutor, ()  -> sendMessages.apply(clientCtx));
            }
        }
        var err = firstError.get();
        if (err != null) {
            throw new AssertionError("send failed under load", err);
        }

        var clientCtxIterator = processedClientContexts.iterator();
        while (clientCtxIterator.hasNext()) {
            var clientCtx = clientCtxIterator.next();
            var wsTestClient = clientCtx.testClient();
            wsTestClient.websocketClientSession().close();

            var messagesSentToClient = clientCtx.messagesSentToClient();
            var messagesSentToApp = clientCtx.messagesSentToApp();
            assertMessagesToApp(wsTestClient.connectionId(), nrMessagesToSend, List.copyOf(messagesSentToApp));
            assertMessagesToClient(wsTestClient, nrMessagesToSend, List.copyOf(messagesSentToClient));
        }

    }

    private static @NonNull String sendMessageFromClientToApp(WebsocketTestClient wsTestClient, String connId)
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
        assert connId != null;
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
