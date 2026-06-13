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
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

public class AppTest {

    private static final Logger log = LoggerFactory.getLogger(AppTest.class);

    private @TempDir Path tempDir;

    private URI wsgwBaseUrl;

    private final ConnectionIdGeneratorMock connectionIdGeneratorMock =  new ConnectionIdGeneratorMock();

    private final HttpClient httpClient = Wsgw.createHttpClient();

    String[] apiKey = new String[] { "XKEY", "asdfqwe" };
    private MockApp mockApp = new MockApp();

    private Wsgw wsgw;

    private WsTestClients wsTestClients = new WsTestClients();

    @BeforeEach
    public void setUp() throws Exception {
        int appPort = mockApp.start(tempDir, apiKey, connectionIdGeneratorMock);
        String appMockUrl = "http://localhost:%d".formatted(appPort);

        wsgw = new Wsgw(appMockUrl, tempDir.resolve("wsgw"), connectionIdGeneratorMock);
        wsgwBaseUrl = URI.create("ws://localhost:%d".formatted(wsgw.start()));
    }

    @AfterEach
    public void tearDown() throws Exception {
        wsTestClients.close();
        wsgw.stop();;
        mockApp.stop();
    }

    @Test
    void canConnectWithValidHeader() throws Exception {
        Function<String, Consumer<String>> messageHandler = clientId -> message -> log.debug("[{}] message received: {}", clientId, message);

        URI wsgwWebscoketServerURI = URI.create(wsgwBaseUrl.toString());
        String connId1 = this.connectionIdGeneratorMock.roll();
        var wsTestClient1 = wsTestClients.connect(wsgwWebscoketServerURI, apiKey, messageHandler.apply("client1"));
        assertThat(wsTestClient1.connectionId()).isEqualTo(connId1);
        String connId2 = this.connectionIdGeneratorMock.roll();
        var wsTestClient2 = wsTestClients.connect(wsgwWebscoketServerURI, apiKey, messageHandler.apply("client2"));
        assertThat(wsTestClient2.connectionId()).isEqualTo(connId2);
        assertFailureBeforeUpgrade("404 from unmapped rest endpoint", HttpServletResponse.SC_NOT_FOUND, "/some-unrelated-rest-endpoint");

        try (Session session1 = wsTestClient1.websocketClientSession()) {
            try (Session session2 = wsTestClient2.websocketClientSession()) {
                session2.getBasicRemote().sendText("Hello from 2");
                session1.getBasicRemote().sendText("Hello from 1");
            }
        }
    }

    private void assertFailureBeforeUpgrade(String assertionContext, int expectedHttpStatusCode, String wsgwPath) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("http://%s:%d", wsgwBaseUrl.getHost(), wsgwBaseUrl.getPort()).concat(wsgwPath)))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.error("Failed to connect to app ({})", wsgwBaseUrl, e);
            throw new RuntimeException(e);
        }
        assertThat(response.statusCode()).as(assertionContext).isEqualTo(expectedHttpStatusCode);
    }

    @Test
    void canNotConnectWithInvalidHeader() throws Exception {
        var invalidAPIKey = new String[] { apiKey[0], apiKey[1].concat("kalap") };
        assertFailureBeforeUpgrade("401 from /connect handshake with invalid key", HttpServletResponse.SC_UNAUTHORIZED, "/connect");
    }

}

