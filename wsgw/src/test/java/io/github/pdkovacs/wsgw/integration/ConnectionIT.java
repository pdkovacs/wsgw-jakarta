package io.github.pdkovacs.wsgw.integration;

import io.github.pdkovacs.wsgw.logging.CtxLogger;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

public class ConnectionIT {

    private static final CtxLogger logger = CtxLogger.of(ConnectionIT.class);

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
    void setsUpConnectionWithValidAPIKey() throws Exception {
        String wsgwServerName = wsgwTestContext.getWsgwServerName();
        String connId1 = this.wsgwTestContext.connectionIdGeneratorMock.roll();
        var wsTestClient1 = wsgwTestContext.wsTestClients.connect(wsgwServerName, wsgwTestContext.apiKey);
        assertThat(wsTestClient1.connectionId()).isEqualTo(connId1);
        String connId2 = this.wsgwTestContext.connectionIdGeneratorMock.roll();
        var wsTestClient2 = wsgwTestContext.wsTestClients.connect(wsgwServerName, wsgwTestContext.apiKey);
        assertThat(wsTestClient2.connectionId()).isEqualTo(connId2);
        assertFailureBeforeUpgrade("404 from unmapped rest endpoint", HttpServletResponse.SC_NOT_FOUND,
                "/some-unrelated-rest-endpoint", wsgwTestContext.apiKey);
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
    void setsUpConnectionWithInvalidAPIKey() throws Exception {
        var invalidAPIKey = new String[]{wsgwTestContext.apiKey[0], wsgwTestContext.apiKey[1].concat("kalap")};
        assertFailureBeforeUpgrade("plain GET to /connect with valid key", HttpServletResponse.SC_NOT_FOUND,
                "/connect", wsgwTestContext.apiKey);
        assertFailureBeforeUpgrade("401 from /connect handshake with invalid key", HttpServletResponse.SC_UNAUTHORIZED,
                "/connect", invalidAPIKey);
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
