package io.github.pdkovacs.wsgw.integration;

import io.github.pdkovacs.wsgw.Configuration;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class ShutdownIT {

    private static final CtxLogger logger = CtxLogger.of(ShutdownIT.class);

    final WsgwTestContext wsgwTestContext = new WsgwTestContext();

    @BeforeEach
    public void setUp(@TempDir Path tempDir) throws Exception {
        var config = new Configuration();
        // Room for both the disconnect notification and POISON behind a relayed message in flight,
        // so that onClose enqueues them without blocking.
        config.setAppwardDispatcherQueueSize(2);
        wsgwTestContext.setUp(tempDir, config);
    }

    @Test
    @Timeout(10)
    void shutdownClosesSessionsAndDisconnectsAtApp() throws Exception {
        var tcLogger = logger.with("test-case", "shutdownClosesSessionsAndDisconnectsAtApp");

        var appDisconnectBlocking = new CountDownLatch(2);
        var unblockAppDisconnect = new CountDownLatch(1);
        try {
            String wsgwServerName = wsgwTestContext.getWsgwServerName();
            this.wsgwTestContext.connectionIdGeneratorMock.roll();
            wsgwTestContext.wsTestClients.connect(wsgwServerName, wsgwTestContext.fakeAppConfig.getApiKey());
            this.wsgwTestContext.connectionIdGeneratorMock.roll();
            wsgwTestContext.wsTestClients.connect(wsgwServerName, wsgwTestContext.fakeAppConfig.getApiKey());

            // Defensive ASSERT
            for (var client : wsgwTestContext.wsTestClients.getClients()) {
                assertThat(client.testClientEndpoint().sessionClosed).isEqualTo(false);
            }
            var appInboxes = wsgwTestContext.getAppInboxes();
            assertThat(appInboxes.size()).isEqualTo(2);
            for (var inbox : appInboxes) {
                assertThat(inbox.size()).isEqualTo(0);
            }

            // ARRANGE
            wsgwTestContext.fakeAppConfig.setDisconnectProcessingImpl(ConnectIT.createWaitImpl(appDisconnectBlocking, unblockAppDisconnect));
        } finally {
            // Have the app Wait for a sizeable period of time in the disconnect implementation
            // so there is some real queue for the dispatchers to drain:
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(Duration.ofSeconds(3));
                    unblockAppDisconnect.countDown();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
            // Start the disconnect procedures assumed with the shutdown
            wsgwTestContext.tearDown();
        }

        for (var client : wsgwTestContext.wsTestClients.getClients()) {
            assertThat(client.testClientEndpoint().sessionClosed).isEqualTo(true);
        }

        // A disconnect notification was sent for all connections
        var appInboxes = wsgwTestContext.getAppInboxes();
        assertThat(appInboxes.size()).isEqualTo(2);
        for (var inbox : appInboxes) {
            assertThat(inbox.stream().findFirst().orElse(null)).isInstanceOf(Message.EndOfStream.class);
        }
    }

    // The disconnect notification is enqueued behind a relayed message the app is still holding, so
    // it has not been handed to the appward HttpClient yet when shutdown closes that client. Closing
    // the client only waits for requests already sent, so the notification reaches the app only if
    // the shutdown waits for the dispatcher to drain.
    @Test
    @Timeout(10)
    void shutdownDisconnectsAtAppBehindStalledRelay() throws Exception {
        var relayInFlight = new CountDownLatch(1);
        var releaseRelay = new CountDownLatch(1);
        String connectionId;
        String message;
        try {
            this.wsgwTestContext.connectionIdGeneratorMock.roll();
            var client = wsgwTestContext.wsTestClients.connect(wsgwTestContext.getWsgwServerName(), wsgwTestContext.fakeAppConfig.getApiKey());
            connectionId = client.connectionId();

            // ARRANGE
            wsgwTestContext.fakeAppConfig.setMessageProcessingImpl(_ -> {
                relayInFlight.countDown();
                try {
                    releaseRelay.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
            message = "relayed over %s".formatted(connectionId);
            client.sendText(message);
            assertThat(relayInFlight.await(WsgwTestContext.SETTLE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                    .as("the relayed message reached the stalled app")
                    .isTrue();
        } finally {
            // Late enough for the shutdown to be past stopping Tomcat, i.e. past the sessions' onClose,
            // and well inside Relays.stop's 5s join.
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(Duration.ofSeconds(2));
                    releaseRelay.countDown();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
            wsgwTestContext.tearDown();
        }

        assertThat(wsgwTestContext.getAppInbox(connectionId))
                .containsExactly(new Message.Text(message), Message.EndOfStream.INSTANCE);
    }
}
