package io.github.pdkovacs.wsgw;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.websocket.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

public class ConnectionTest {

    private static final Logger log = LoggerFactory.getLogger(ConnectionTest.class);

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
    void canConnectWithValidHeader() throws Exception {
        URI wsgwWebscoketServerURI = URI.create(wsgwTestContext.wsgwBaseUrl.toString());
        String connId1 = this.wsgwTestContext.connectionIdGeneratorMock.roll();
        var wsTestClient1 = wsgwTestContext.wsTestClients.connect(wsgwWebscoketServerURI, wsgwTestContext.apiKey);
        assertThat(wsTestClient1.connectionId()).isEqualTo(connId1);
        String connId2 = this.wsgwTestContext.connectionIdGeneratorMock.roll();
        var wsTestClient2 = wsgwTestContext.wsTestClients.connect(wsgwWebscoketServerURI, wsgwTestContext.apiKey);
        assertThat(wsTestClient2.connectionId()).isEqualTo(connId2);
        assertFailureBeforeUpgrade("404 from unmapped rest endpoint", HttpServletResponse.SC_NOT_FOUND, "/some-unrelated-rest-endpoint");
    }

    private void assertFailureBeforeUpgrade(String assertionContext, int expectedHttpStatusCode, String wsgwPath) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("http://%s:%d", wsgwTestContext.wsgwBaseUrl.getHost(), wsgwTestContext.wsgwBaseUrl.getPort()).concat(wsgwPath)))
                .build();
        HttpResponse<String> response;
        try {
            response = wsgwTestContext.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.error("Failed to connect to app ({})", wsgwTestContext.wsgwBaseUrl, e);
            throw new RuntimeException(e);
        }
        assertThat(response.statusCode()).as(assertionContext).isEqualTo(expectedHttpStatusCode);
    }

    @Test
    void canNotConnectWithInvalidHeader() throws Exception {
        var invalidAPIKey = new String[] {wsgwTestContext.apiKey[0], wsgwTestContext.apiKey[1].concat("kalap") };
        assertFailureBeforeUpgrade("401 from /connect handshake with invalid key", HttpServletResponse.SC_UNAUTHORIZED, "/connect");
    }

}

