package io.github.pdkovacs.wsgw.integration;

import io.github.pdkovacs.wsgw.logging.CtxLogger;
import io.github.pdkovacs.wsgw.Configuration;
import jakarta.servlet.http.HttpServletResponse;
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

@Timeout(5)
public class ConnectionIT {

    private static final CtxLogger logger = CtxLogger.of(ConnectionIT.class);

    final WsgwTestContext wsgwTestContext = new WsgwTestContext();

    @AfterEach
    public void tearDown() throws Exception {
        wsgwTestContext.tearDown();
    }

    @Test
    void setsUpConnectionWithValidAPIKey(@TempDir Path tempDir) throws Exception {
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
    void setsUpConnectionWithInvalidAPIKey(@TempDir Path tempDir) throws Exception {
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

        var appConnectBlocking = new CountDownLatch(2);
        var unblockAppConnect = new CountDownLatch(1);
        try {
            assertThat(wsgwTestContext.meters.connectTimeouts()).isEqualTo(0);
            wsgwTestContext.fakeAppConfig.setConnectProcessingImpl(createWaitImpl(appConnectBlocking, unblockAppConnect));
            assertFailureBeforeUpgrade("504 from /connect handshake time-outing", HttpServletResponse.SC_GATEWAY_TIMEOUT,
                    "/connect", wsgwTestContext.fakeAppConfig.getApiKey());
            assertThat(wsgwTestContext.meters.connectTimeouts()).isEqualTo(1);
        } finally {
            unblockAppConnect.countDown();
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
