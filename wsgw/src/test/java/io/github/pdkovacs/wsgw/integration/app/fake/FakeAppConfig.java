package io.github.pdkovacs.wsgw.integration.app.fake;

import java.nio.file.Path;
import java.time.Duration;

public class FakeAppConfig {

    private final Path tomcatBaseDir;
    private final String[] apiKey;

    private Runnable connectProcessingImpl;
    private Runnable disconnectProcessingImpl;

    public FakeAppConfig(Path tomcatBaseDir, String[] apiKey) {
        this.tomcatBaseDir = tomcatBaseDir;
        this.apiKey = apiKey;
    }

    public Path getTomcatBaseDir() {
        return tomcatBaseDir;
    }

    public String[] getApiKey() {
        return apiKey;
    }

    public Runnable getConnectProcessingImpl() {
        return connectProcessingImpl;
    }

    public void setConnectProcessingImpl(Runnable connectProcessingImpl) {
        this.connectProcessingImpl = connectProcessingImpl;
    }

    public Runnable getDisconnectProcessingImpl() {
        return disconnectProcessingImpl;
    }

    public void setDisconnectProcessingImpl(Runnable disconnectProcessingImpl) {
        this.disconnectProcessingImpl = disconnectProcessingImpl;
    }
}