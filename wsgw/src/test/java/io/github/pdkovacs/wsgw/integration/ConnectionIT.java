package io.github.pdkovacs.wsgw.integration;

import io.github.pdkovacs.wsgw.logging.CtxLogger;
import io.github.pdkovacs.wsgw.Configuration;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.websocket.DeploymentException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@Timeout(5)
public class ConnectionIT {

    private static final CtxLogger logger = CtxLogger.of(ConnectionIT.class);

    final WsgwTestContext wsgwTestContext = new WsgwTestContext();

    @AfterEach
    public void tearDown() throws Exception {
        wsgwTestContext.tearDown();
    }

    @Test
    void connectSucceedsWithValidAPIKey(@TempDir Path tempDir) throws Exception {
        wsgwTestContext.setUp(tempDir);

        String wsgwServerName = wsgwTestContext.getWsgwServerName();
        String connId1 = this.wsgwTestContext.connectionIdGeneratorMock.roll();
        var wsTestClient1 = wsgwTestContext.wsTestClients.connect(wsgwServerName, wsgwTestContext.fakeAppConfig.getApiKey());
        assertThat(wsTestClient1.connectionId()).isEqualTo(connId1);
        String connId2 = this.wsgwTestContext.connectionIdGeneratorMock.roll();
        var wsTestClient2 = wsgwTestContext.wsTestClients.connect(wsgwServerName, wsgwTestContext.fakeAppConfig.getApiKey());
        assertThat(wsTestClient2.connectionId()).isEqualTo(connId2);
        assertFailureBeforeUpgrade("404 from unmapped rest endpoint", HttpServletResponse.SC_NOT_FOUND,
                "/some-unrelated-rest-endpoint", wsgwTestContext.fakeAppConfig.getApiKey());
    }

    private void assertFailureBeforeUpgrade(String assertionContext, int expectedHttpStatusCode, String wsgwPath,
                                            String[] apiKey) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://%s"
                        .formatted(wsgwTestContext.getWsgwServerName())
                        .concat(wsgwPath)))
                .header(apiKey[0], apiKey[1])
                .build();
        HttpResponse<String> response;
        try {
            response = wsgwTestContext.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            logger.error("Failed to connect to app ({})", wsgwTestContext.getWsgwServerName(), e);
            throw new RuntimeException(e);
        }
        assertThat(response.statusCode()).as(assertionContext).isEqualTo(expectedHttpStatusCode);
    }

    @Test
    void connectFailsWithInvalidAPIKey(@TempDir Path tempDir) throws Exception {
        wsgwTestContext.setUp(tempDir);

        var invalidAPIKey = new String[]{wsgwTestContext.fakeAppConfig.getApiKey()[0], wsgwTestContext.fakeAppConfig.getApiKey()[1].concat("kalap")};
        assertFailureBeforeUpgrade("plain GET to /connect with valid key", HttpServletResponse.SC_NOT_FOUND,
                "/connect", wsgwTestContext.fakeAppConfig.getApiKey());
        assertFailureBeforeUpgrade("401 from /connect handshake with invalid key", HttpServletResponse.SC_UNAUTHORIZED,
                "/connect", invalidAPIKey);
    }

    @Test
    void timeoutsOnAppWith504(@TempDir Path tempDir) throws Exception {
        var config = new Configuration();
        config.setBaseDir(tempDir.resolve("wsgw"));

        var timeOut = Duration.ofSeconds(3);

        config.setConnectWaitTimeout(timeOut.minus(Duration.ofSeconds(1)));
        wsgwTestContext.setUp(tempDir, config);

        var appConnectImplBlocking = new CountDownLatch(2);
        var unblockAppConnect = new CountDownLatch(1);
        try {
            assertThat(wsgwTestContext.meters.connectTimeouts()).isEqualTo(0);
            wsgwTestContext.fakeAppConfig.setConnectProcessingImpl(createWaitImpl(appConnectImplBlocking, unblockAppConnect));
            assertFailureBeforeUpgrade("504 from /connect handshake time-outing", HttpServletResponse.SC_GATEWAY_TIMEOUT,
                    "/connect", wsgwTestContext.fakeAppConfig.getApiKey());
            assertThat(wsgwTestContext.meters.connectTimeouts()).isEqualTo(1);
        } finally {
            unblockAppConnect.countDown();
        }
    }

    @Test
    void inflightConnectsGauge(@TempDir Path tempDir) throws Exception {
        var tcLogger = logger.with("test-case", "inflightConnectsGauge");
        // ARRANGE
        var connectionEstablished = new CountDownLatch(2);
        var appConnectImplBlocking = new CountDownLatch(2);
        var unblockAppConnect = new CountDownLatch(1);
        try {
            wsgwTestContext.setUp(tempDir);
            wsgwTestContext.fakeAppConfig.setConnectProcessingImpl(ConnectionIT.createWaitImpl(appConnectImplBlocking, unblockAppConnect));

            // ACT
            for (var i = 0; i < 2; i++) {
                connectChecked(connectionEstablished, false);
            }
            appConnectImplBlocking.await();

            // ASSERT
            assertThat(wsgwTestContext.meters.inflightConnects()).isEqualTo(2);
        } finally {
            unblockAppConnect.countDown();
            connectionEstablished.await();
        }
    }

    @Test
    void excessInflightConnectThrows503(@TempDir Path tempDir) throws Exception {
        var tcLogger = logger.with("test-case", "inflightConnectInExcessThrows503");
        // ARRANGE
        int maxInFlightConnects = 1;
        var config = new Configuration();
        config.setMaxInFlightConnects(maxInFlightConnects);

        var connectionEstablished = new CountDownLatch(1);
        var appConnectImplBlocking = new CountDownLatch(1);
        var unblockAppConnectImpl = new CountDownLatch(1);
        try {
            wsgwTestContext.setUp(tempDir, config);
            wsgwTestContext.fakeAppConfig.setConnectProcessingImpl(ConnectionIT.createWaitImpl(appConnectImplBlocking, unblockAppConnectImpl));

            // ACT
            connectChecked(connectionEstablished, false);
            appConnectImplBlocking.await();

            try {
                connectChecked(connectionEstablished, true);
                fail("Expected exception: HTTP 503");
            } catch (Exception e) {
                assertThat(e).isInstanceOf(DeploymentException.class);
                var deploymentException = (DeploymentException) e;
                assertThat(deploymentException).hasMessageContaining("[503]");
            }

            tcLogger.debug("assert...");
            assertThat(wsgwTestContext.meters.inflightConnects()).isEqualTo(1);
        } finally {
            unblockAppConnectImpl.countDown();
            connectionEstablished.await();
        }
    }

    private void connectChecked(CountDownLatch connectionEstablished, boolean join) throws Exception {
        String wsgwServerName = wsgwTestContext.getWsgwServerName();
        final Exception[] savedException = new Exception[1];
        Thread t = Thread.ofVirtual().start(() -> {
            this.wsgwTestContext.connectionIdGeneratorMock.roll();
            try {
                wsgwTestContext.wsTestClients.connect(
                        wsgwServerName,
                        wsgwTestContext.fakeAppConfig.getApiKey(),
                        connectionEstablished
                );
            } catch (Exception e) {
                savedException[0] = e;
                logger.error("[connectChecked]: test client failed to connect", e);
            }
        });
        if  (join) {
            t.join();
            if (savedException[0] != null) {
                throw savedException[0];
            }
        }
    }

    static Runnable createWaitImpl(CountDownLatch readyForBlocking, CountDownLatch unblock) {
        return () -> {
            var mLogger = logger.with("method", "createWaitImpl");
            try {
                readyForBlocking.countDown();
                mLogger.debug("about to get busy...");
                unblock.await();
                mLogger.debug("no longer busy");
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        };
    }
}
