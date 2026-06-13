package io.github.pdkovacs.wsgw;

import io.github.pdkovacs.wsgw.fake.app.FakeApp;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;

public class WsgwTestContext {

    final ConnectionIdGeneratorMock connectionIdGeneratorMock = new ConnectionIdGeneratorMock();
    final HttpClient httpClient = RequestToApp.createHttpClient();
    final String[] apiKey = new String[]{"XKEY", "asdfqwe"};
    final FakeApp fakeApp = new FakeApp();
    final WsTestClients wsTestClients = new WsTestClients();

    private Wsgw wsgw;

    URI wsgwBaseUrl;

    public WsgwTestContext() {
    }

    public void setUp(Path tempDir) throws Exception {
        int appPort = fakeApp.start(tempDir, apiKey);
        String appMockUrl = "http://localhost:%d".formatted(appPort);

        wsgw = new Wsgw(appMockUrl, tempDir.resolve("wsgw"), connectionIdGeneratorMock);
        wsgwBaseUrl = URI.create("ws://localhost:%d".formatted(wsgw.start()));
    }

    public void tearDown() throws Exception {
        wsTestClients.close();
        wsgw.stop();
        fakeApp.stop();
    }
}