package io.github.pdkovacs.wsgw.integration;

import io.github.pdkovacs.wsgw.Configuration;
import io.github.pdkovacs.wsgw.Wsgw;
import io.github.pdkovacs.wsgw.appward.Request;
import io.github.pdkovacs.wsgw.integration.app.fake.FakeApp;
import io.github.pdkovacs.wsgw.integration.app.fake.FakeAppConfig;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;

public class WsgwTestContext {

    private static final CtxLogger logger = CtxLogger.of(WsgwTestContext.class);
    public static final int APPWARD_DISPATCHER_QUEUE_SIZE = 1;

    record Meters(MeterRegistry registry) {
        int inflightConnects() {
            return (int) registry.get("wsgw.connects.inflight").tag("leg", "connect")
                    .gauge().value();
        }
    }

    private final FakeApp fakeApp = new FakeApp();

    final ConnectionIdGeneratorMock connectionIdGeneratorMock = new ConnectionIdGeneratorMock();
    final HttpClient httpClient = Request.createHttpClient();
    final WsTestClients wsTestClients = new WsTestClients();
    Meters meters;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private Wsgw wsgw;
    private String wsgwServerName;

    FakeAppConfig fakeAppConfig;

    public WsgwTestContext() {
    }

    public void setUp(Path tempDir, Configuration wsgwConfig) throws Exception {
        fakeAppConfig = new FakeAppConfig(tempDir, new String[]{"XKEY", "asdfqwe"});
        int appPort = fakeApp.start(fakeAppConfig);
        String appBaseUrl = "http://localhost:%d".formatted(appPort);
        wsgwConfig.setAppBaseUrl(appBaseUrl);
        wsgwConfig.setBaseDir(tempDir.resolve("wsgw"));

        wsgw = new Wsgw(wsgwConfig, meterRegistry, connectionIdGeneratorMock);
        wsgwServerName = "localhost:%d".formatted(wsgw.start());
        meters = new Meters(meterRegistry);
    }

    public void setUp(Path tempDir) throws Exception {
        var config = new Configuration();
        config.setBaseDir(tempDir.resolve("wsgw"));
        config.setAppwardDispatcherQueueSize(1);
        setUp(tempDir, config);
    }

    public void tearDown() throws Exception {
        logger.debug("Tearing down WsgwTestContext");
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

    public List<BlockingQueue<Message>> getAppInboxes() {
        return this.fakeApp.getInboxes();
    }
}