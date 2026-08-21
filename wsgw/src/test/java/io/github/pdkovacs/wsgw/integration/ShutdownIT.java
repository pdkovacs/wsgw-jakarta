package io.github.pdkovacs.wsgw.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

public class ShutdownIT {

    final WsgwTestContext wsgwTestContext = new WsgwTestContext();

    @BeforeEach
    public void setUp(@TempDir Path tempDir) throws Exception {
        wsgwTestContext.setUp(tempDir);
    }

    @Test
    @Timeout(10)
    void shutdownDisconnectsBothClientAndApp() throws Exception {
        try {
            // ARRANGE
            String wsgwServerName = wsgwTestContext.getWsgwServerName();
            String connId1 = this.wsgwTestContext.connectionIdGeneratorMock.roll();
            var wsTestClient1 = wsgwTestContext.wsTestClients.connect(wsgwServerName, wsgwTestContext.fakeAppConfig.getApiKey());
            assertThat(wsTestClient1.connectionId()).isEqualTo(connId1);
            String connId2 = this.wsgwTestContext.connectionIdGeneratorMock.roll();
            var wsTestClient2 = wsgwTestContext.wsTestClients.connect(wsgwServerName, wsgwTestContext.fakeAppConfig.getApiKey());
            assertThat(wsTestClient2.connectionId()).isEqualTo(connId2);

            // Defensive ASSERT
            for (var client : wsgwTestContext.wsTestClients.getClients()) {
                assertThat(client.testClientEndpoint().sessionClosed).isEqualTo(false);
            }
            var appInboxes = wsgwTestContext.getAppInboxes();
            assertThat(appInboxes.size()).isEqualTo(2);
            for (var inbox : appInboxes ) {
                assertThat(inbox.size()).isEqualTo(0);
            }

            // ARRANGE
            wsgwTestContext.fakeAppConfig.setDisconnectProcessingImpl(ConnectionIT.createWaitImpl(Duration.ofSeconds(3)));
        } finally {
            // ACT
            wsgwTestContext.tearDown();
        }

        for (var client : wsgwTestContext.wsTestClients.getClients()) {
            assertThat(client.testClientEndpoint().sessionClosed).isEqualTo(true);
        }

        // A disconnect notification was sent for all connections
        var appInboxes = wsgwTestContext.getAppInboxes();
        assertThat(appInboxes.size()).isEqualTo(2);
        for (var inbox : appInboxes ) {
            assertThat(inbox.stream().findFirst().orElse(null)).isInstanceOf(Message.EndOfStream.class);
        }
    }
}
