package io.github.pdkovacs.wsgw.integration;

import io.github.pdkovacs.wsgw.Configuration;
import io.github.pdkovacs.wsgw.Wsgw;
import io.github.pdkovacs.wsgw.appward.Request;
import io.github.pdkovacs.wsgw.integration.app.fake.FakeApp;
import io.github.pdkovacs.wsgw.integration.app.fake.FakeAppConfig;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import org.jspecify.annotations.NonNull;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;

public class WsgwTestContext {

    private static final CtxLogger logger = CtxLogger.of(WsgwTestContext.class);
    public static final int APPWARD_DISPATCHER_QUEUE_SIZE = 1;

    private final FakeApp fakeApp = new FakeApp();

    final ConnectionIdGeneratorMock connectionIdGeneratorMock = new ConnectionIdGeneratorMock();
    final HttpClient httpClient = Request.createHttpClient();
    final WsTestClients wsTestClients = new WsTestClients();

    private Wsgw wsgw;
    private String wsgwServerName;

    FakeAppConfig fakeAppConfig;

    public WsgwTestContext() {}

    public void setUp(Path tempDir, Configuration wsgwConfig) throws Exception {
        fakeAppConfig = new FakeAppConfig(tempDir, new String[] { "XKEY", "asdfqwe" });
        int appPort = fakeApp.start(fakeAppConfig);
        String appBaseUrl = "http://localhost:%d".formatted(appPort);
        wsgwConfig.setAppBaseUrl(appBaseUrl);
        wsgwConfig.setBaseDir(tempDir.resolve("wsgw"));

        wsgw = new Wsgw(wsgwConfig, connectionIdGeneratorMock);
        wsgwServerName = "localhost:%d".formatted(wsgw.start());
    }

    public void setUp(Path tempDir) throws Exception {
        var config = new Configuration();
        config.setBaseDir(tempDir.resolve("wsgw"));
        config.setAppwardDispatcherQueueSize(1);
        setUp(tempDir, config);
    }

    public void tearDown() throws Exception {
        logger.debug("Tearing down WsgwTestContext");
        wsTestClients.close();
        wsgw.stop();
        fakeApp.stop();
        httpClient.close();
    }

    public String getWsgwServerName() {
        return wsgwServerName;
    }

    public BlockingQueue<Message> getAppInbox(String connectionId) {
        return fakeApp.getConnection(connectionId).getMessageInbox();
    }
}