package io.github.pdkovacs.wsgw;

import jakarta.websocket.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import io.github.pdkovacs.wsgw.logging.CtxLogger;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

public class MessageTest {

    private static final CtxLogger logger = CtxLogger.of(MessageTest.class);

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
    void relaysAMessageToApp() throws Exception {
        URI wsgwWebscoketServerURI = URI.create(wsgwTestContext.wsgwBaseUrl.toString());
        String connId = this.wsgwTestContext.connectionIdGeneratorMock.roll();
        var wsTestClient = wsgwTestContext.wsTestClients.connect(wsgwWebscoketServerURI, wsgwTestContext.apiKey);

        final String messageToApp = "Hello from client";

        Session session = wsTestClient.websocketClientSession();
        session.getBasicRemote().sendText(messageToApp);

        logger.debug("Message sent to app over {}: {}", connId, messageToApp);

        Message msgInApp = wsgwTestContext.getAppInbox(connId).take();
        String text = switch (msgInApp) {
            case Message.Text(String t) -> t;
            case Message.EndOfStream _ ->
                fail("Expected message %s, got EndOfStream".formatted(messageToApp));
        };
        assertThat(text).isEqualTo(messageToApp);
    }

    @Test
    @Timeout(3)
    void sendsAMessageToClient() throws Exception {
        try {
            URI wsgwWebscoketServerURI = URI.create(wsgwTestContext.wsgwBaseUrl.toString());
            String connId = this.wsgwTestContext.connectionIdGeneratorMock.roll();
            var wsTestClient = wsgwTestContext.wsTestClients.connect(wsgwWebscoketServerURI, wsgwTestContext.apiKey);

            final String messageFromApp = "Hello from app";
            logger.debug("Assembling request...");
            var msgToClientUrl = wsgwTestContext.getWsgwUrl("http", "/%s/%s".formatted(WsgwPaths.MESSAGE_FROM_APP, connId));
            HttpRequest request = HttpRequest.newBuilder(msgToClientUrl)
                    .POST(HttpRequest.BodyPublishers.ofString(messageFromApp))
                    .build();
            logger.debug("Request assembled");
            wsgwTestContext.httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            logger.debug("Request sent");

            var msgInApp = wsTestClient.messageInbox().take();
            String text = switch (msgInApp) {
                case Message.Text(String t) -> t;
                case Message.EndOfStream _ ->
                    fail("Expected message %s, got EndOfStream".formatted(msgInApp));
            };
            assertThat(text).isEqualTo(messageFromApp);
        } catch (Exception e) {
            logger.error("Exception occurred during sending a message", e);
            throw new RuntimeException(e);
        }
    }

}
