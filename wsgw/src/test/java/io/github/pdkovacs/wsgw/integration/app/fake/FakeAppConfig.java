package io.github.pdkovacs.wsgw.integration.app.fake;

import java.nio.file.Path;
import java.time.Duration;

public class FakeAppConfig {

    private final Path tomcatBaseDir;
    private final String[] apiKey;

    private Duration connectProcessingDuration;

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

    public Duration getConnectProcessingDuration() {
        return connectProcessingDuration;
    }

    public void setConnectProcessingDuration(Duration connectProcessingDuration) {
        this.connectProcessingDuration = connectProcessingDuration;
    }
}