package io.github.pdkovacs.wsgw.integration;

import io.github.pdkovacs.wsgw.logging.CtxLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

public class ShutdownIT {

    private static final CtxLogger logger = CtxLogger.of(ShutdownIT.class);

    final WsgwTestContext wsgwTestContext = new WsgwTestContext();

    @BeforeEach
    public void setUp(@TempDir Path tempDir) throws Exception {
        wsgwTestContext.setUp(tempDir);
    }

    @Test
    @Timeout(10)
    void shutdownDisconnectsBothClientAndApp() throws Exception {
        var tcLogger = logger.with("test-case", "shutdownDisconnectsBothClientAndApp");

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
            wsgwTestContext.fakeAppConfig.setDisconnectProcessingImpl(ConnectionIT.createWaitImpl(appDisconnectBlocking, unblockAppDisconnect));
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
}
