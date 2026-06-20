package io.github.pdkovacs.wsgw;

import jakarta.websocket.Session;
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

public class MessageTest {

    private static final Logger logger = LoggerFactory.getLogger(MessageTest.class);

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
    void canRelayAMessageToApp() throws Exception {
        URI wsgwWebscoketServerURI = URI.create(wsgwTestContext.wsgwBaseUrl.toString());
        String connId = this.wsgwTestContext.connectionIdGeneratorMock.roll();
        var wsTestClient = wsgwTestContext.wsTestClients.connect(wsgwWebscoketServerURI, wsgwTestContext.apiKey);

        final String messageToApp = "Hello from client";

        Session session = wsTestClient.websocketClientSession();
        session.getBasicRemote().sendText(messageToApp);

        logger.debug("Message sent to app over {}: {}", connId, messageToApp);

        String msgInApp = wsgwTestContext.fakeApp.getConnection(connId).getMessages().take();
        assertThat(msgInApp).isEqualTo(messageToApp);
    }

    @Test
    @Timeout(3)
    void canRelayAMessageFromApp() throws Exception {
        URI wsgwWebscoketServerURI = URI.create(wsgwTestContext.wsgwBaseUrl.toString());
        String connId = this.wsgwTestContext.connectionIdGeneratorMock.roll();
        var wsTestClient = wsgwTestContext.wsTestClients.connect(wsgwWebscoketServerURI, wsgwTestContext.apiKey);

        final String messageFromApp = "Hello from app";
        var msgToClientUrl = wsgwTestContext.getWsgwUrl("http", "/%s/%s".formatted(WsgwPaths.MESSAGE_FROM_APP, connId));
        HttpRequest request = HttpRequest.newBuilder(msgToClientUrl)
                .POST(HttpRequest.BodyPublishers.ofString(messageFromApp))
                .build();
        wsgwTestContext.httpClient.send(request, HttpResponse.BodyHandlers.discarding());

        String message = wsTestClient.messageInbox().take();
        assertThat(message).isEqualTo(messageFromApp);
    }

}
