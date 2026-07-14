package io.github.pdkovacs.wsgw.integration;

import io.github.pdkovacs.wsgw.WsgwPaths;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

public class ConnectionIT {

    private static final Logger log = LoggerFactory.getLogger(ConnectionIT.class);

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
            log.error("Failed to connect to app ({})", wsgwTestContext.getWsgwServerName(), e);
            throw new RuntimeException(e);
        }
        assertThat(response.statusCode()).as(assertionContext).isEqualTo(expectedHttpStatusCode);
    }

    @Test
    void setsUpConnectionWithInvalidAPIKey() throws Exception {
        var invalidAPIKey = new String[] { wsgwTestContext.apiKey[0], wsgwTestContext.apiKey[1].concat("kalap") };
        assertFailureBeforeUpgrade("plain GET to /connect with valid key", HttpServletResponse.SC_NOT_FOUND,
                "/connect", wsgwTestContext.apiKey);
        assertFailureBeforeUpgrade("401 from /connect handshake with invalid key", HttpServletResponse.SC_UNAUTHORIZED,
                "/connect", invalidAPIKey);
    }

    @Test
    @Timeout(3)
    void sendsDisconnectOnClientDisconneting() throws Exception {
        String wsgwServerName = wsgwTestContext.getWsgwServerName();
        String connId1 = this.wsgwTestContext.connectionIdGeneratorMock.roll();
        var wsTestClient1 = wsgwTestContext.wsTestClients.connect(wsgwServerName, wsgwTestContext.apiKey);
        String connId2 = this.wsgwTestContext.connectionIdGeneratorMock.roll();
        var wsTestClient2 = wsgwTestContext.wsTestClients.connect(wsgwServerName, wsgwTestContext.apiKey);
        wsTestClient1.websocketClientSession().close();
        var nextMessage = wsgwTestContext.getAppInbox(connId1).take();
        if (nextMessage instanceof Message.EndOfStream) {
            assertThat("EndOfStream");
        } else {
            fail("Expected EndOfStream, got %s".formatted(nextMessage));
        }
        wsTestClient2.websocketClientSession().close();
        var nextMessage1 = wsgwTestContext.getAppInbox(connId2).take();
        if (nextMessage1 instanceof Message.EndOfStream) {
            assertThat("EndOfStream");
        } else {
            fail("Expected EndOfStream, got %s".formatted(nextMessage));
        }
    }

    @Test
    @Timeout(3)
    void disconnectsClientConnectionOnRequest() throws Exception {
        String wsgwServerName = wsgwTestContext.getWsgwServerName();

        String connId1 = this.wsgwTestContext.connectionIdGeneratorMock.roll();
        var wsTestClient1 = wsgwTestContext.wsTestClients.connect(wsgwServerName, wsgwTestContext.apiKey);
        String connId2 = this.wsgwTestContext.connectionIdGeneratorMock.roll();
        var wsTestClient2 = wsgwTestContext.wsTestClients.connect(wsgwServerName, wsgwTestContext.apiKey);

        var disconnectFromAppUrl1 = new URI("http://%s/%s/%s".formatted(wsgwServerName, WsgwPaths.DISCONNECT_FROM_APP, connId1));
        HttpRequest request1 = HttpRequest.newBuilder(disconnectFromAppUrl1)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        wsgwTestContext.httpClient.send(request1, HttpResponse.BodyHandlers.discarding());

        var disconnectFromAppUrl2 = new URI("http://%s/%s/%s".formatted(wsgwServerName, WsgwPaths.DISCONNECT_FROM_APP, connId2));
        HttpRequest request2 = HttpRequest.newBuilder(disconnectFromAppUrl2)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        wsgwTestContext.httpClient.send(request2, HttpResponse.BodyHandlers.discarding());

        var nextMessage1 = wsTestClient1.messageInbox().take();
        log.debug("nextMessage={}", nextMessage1);
        if (nextMessage1 instanceof Message.EndOfStream) {
            assertThat("EndOfStream");
        } else {
            fail("Expected EndOfStream, got %s".formatted(nextMessage1.getClass().getName()));
        }

        var nextMessage2 = wsTestClient2.messageInbox().take();
        log.debug("nextMessage={}", nextMessage2);
        if (nextMessage2 instanceof Message.EndOfStream) {
            assertThat("EndOfStream");
        } else {
            fail("Expected EndOfStream, got %s".formatted(nextMessage2.getClass().getName()));
        }
    }

}
