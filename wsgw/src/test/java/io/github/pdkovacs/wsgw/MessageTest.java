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
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

public class MessageTest {

    private static final Logger log = LoggerFactory.getLogger(MessageTest.class);

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

        String msgInApp = wsgwTestContext.fakeApp.getConnection(connId).getMessages().take();
        assertThat(msgInApp).isEqualTo(messageToApp);
    }
}
