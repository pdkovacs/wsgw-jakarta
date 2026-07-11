package io.github.pdkovacs.wsgw;

import io.github.pdkovacs.wsgw.appside.RequestToApp;
import io.github.pdkovacs.wsgw.fake.app.FakeApp;
import io.github.pdkovacs.wsgw.logging.CtxLogger;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;

public class WsgwTestContext {

    private static final CtxLogger logger = CtxLogger.of(WsgwTestContext.class);

    private final FakeApp fakeApp = new FakeApp();

    final ConnectionIdGeneratorMock connectionIdGeneratorMock = new ConnectionIdGeneratorMock();
    final HttpClient httpClient = RequestToApp.createHttpClient();
    final String[] apiKey = new String[] { "XKEY", "asdfqwe" };
    final WsTestClients wsTestClients = new WsTestClients();

    private Wsgw wsgw;

    private String wsgwServerName;

    public WsgwTestContext() {}

    public void setUp(Path tempDir) throws Exception {
        int appPort = fakeApp.start(tempDir, apiKey);
        String appBaseUrl = "http://localhost:%d".formatted(appPort);

        wsgw = new Wsgw(new Configuration(appBaseUrl, tempDir.resolve("wsgw")), connectionIdGeneratorMock);
        wsgwServerName = "localhost:%d".formatted(wsgw.start());
    }

    public void tearDown() throws Exception {
        logger.debug("Tearing down WsgwTestContext");
        wsTestClients.close();
        wsgw.stop();
        fakeApp.stop();
    }

    public String getWsgwServerName() {
        return wsgwServerName;
    }

    public BlockingQueue<Message> getAppInbox(String connectionId) {
        return fakeApp.getConnection(connectionId).getMessageInbox();
    }
}