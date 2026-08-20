package io.github.pdkovacs.wsgw.integration;

import io.github.pdkovacs.wsgw.WsgwPaths;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

@Timeout(5)
public class DisconnectionIT {

    private static final CtxLogger logger = CtxLogger.of(DisconnectionIT.class);

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
    void sendsDisconnectOnClientDisconneting(@TempDir Path tempDir) throws Exception {
        String wsgwServerName = wsgwTestContext.getWsgwServerName();
        String connId1 = this.wsgwTestContext.connectionIdGeneratorMock.roll();
        var wsTestClient1 = wsgwTestContext.wsTestClients.connect(wsgwServerName, wsgwTestContext.fakeAppConfig.getApiKey());
        String connId2 = this.wsgwTestContext.connectionIdGeneratorMock.roll();
        var wsTestClient2 = wsgwTestContext.wsTestClients.connect(wsgwServerName, wsgwTestContext.fakeAppConfig.getApiKey());
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
    void disconnectsClientConnectionOnRequest(@TempDir Path tempDir) throws Exception {
        String wsgwServerName = wsgwTestContext.getWsgwServerName();

        String connId1 = this.wsgwTestContext.connectionIdGeneratorMock.roll();
        var wsTestClient1 = wsgwTestContext.wsTestClients.connect(wsgwServerName, wsgwTestContext.fakeAppConfig.getApiKey());
        String connId2 = this.wsgwTestContext.connectionIdGeneratorMock.roll();
        var wsTestClient2 = wsgwTestContext.wsTestClients.connect(wsgwServerName, wsgwTestContext.fakeAppConfig.getApiKey());

        var disconnectFromAppUrl1 = new URI("http://%s/%s/%s".formatted(wsgwServerName, WsgwPaths.DISCONNECT_FROM_APP, connId1));
        HttpRequest request1 = HttpRequest.newBuilder(disconnectFromAppUrl1)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        wsgwTestContext.httpClient.send(request1, HttpResponse.BodyHandlers.discarding());

        var disconnectFromAppUrl2 = new URI("http://%s/%s/%s".formatted(wsgwServerName, WsgwPaths.DISCONNECT_FROM_APP, connId2));
        HttpRequest request2 = HttpRequest.newBuilder(disconnectFromAppUrl2)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        var response = wsgwTestContext.httpClient.send(request2, HttpResponse.BodyHandlers.discarding());

        assertThat(response).isNotNull();
        assertThat(response.statusCode()).isEqualTo(200);

        var nextMessage1 = wsTestClient1.messageInbox().take();
        logger.debug("nextMessage={}", nextMessage1);
        if (nextMessage1 instanceof Message.EndOfStream) {
            assertThat("EndOfStream");
        } else {
            fail("Expected EndOfStream, got %s".formatted(nextMessage1.getClass().getName()));
        }

        var nextMessage2 = wsTestClient2.messageInbox().take();
        logger.debug("nextMessage={}", nextMessage2);
        if (nextMessage2 instanceof Message.EndOfStream) {
            assertThat("EndOfStream");
        } else {
            fail("Expected EndOfStream, got %s".formatted(nextMessage2.getClass().getName()));
        }
    }
}
