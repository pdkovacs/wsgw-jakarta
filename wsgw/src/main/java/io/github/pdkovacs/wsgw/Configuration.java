package io.github.pdkovacs.wsgw;

import java.nio.file.Path;
import java.time.Duration;

public class Configuration {

    // Where Tomcat keeps its scratch/work area. Without this, embedded Tomcat
    // defaults to a "tomcat.<port>" directory under the process working dir,
    // littering the source tree.
    private Path baseDir;

    private String appBaseUrl;

    private int appwardDispatcherQueueSize = 20;

    private Duration connectWaitTimeout = Duration.ofSeconds(10);

    private int maxInFlightConnects = 10000;

    private Duration connectFailureCountWindow = Duration.ofSeconds(30);

    private int connectFailurePreemptThreshold = 30;

    private Duration preemptHoldDown =  Duration.ofSeconds(30);

    public String getAppBaseUrl() {
        return appBaseUrl;
    }

    public void setAppBaseUrl(String appBaseUrl) {
        this.appBaseUrl = appBaseUrl;
    }

    public Path getBaseDir() {
        return baseDir != null ? baseDir : Path.of(System.getProperty("java.io.tmpdir"), "wsgw-tomcat");
    }

    public void setBaseDir(Path baseDir) {
        this.baseDir = baseDir;
    }

    public int getAppwardDispatcherQueueSize() {
        return appwardDispatcherQueueSize;
    }

    public void setAppwardDispatcherQueueSize(int appwardDispatcherQueueSize) {
        this.appwardDispatcherQueueSize = appwardDispatcherQueueSize;
    }

    public Duration getPushToClientWaitTimeout() {
        return Duration.ofSeconds(10);
    }

    public Duration getPushWaitForSendMessageDesaturation() {
        return Duration.ofSeconds(10);
    }

    public Duration getConnectWaitTimeout() {
        return connectWaitTimeout;
    }

    public void setConnectWaitTimeout(Duration connectWaitTimeout) {
        this.connectWaitTimeout = connectWaitTimeout;
    }

    public int getMaxInFlightConnects() {
        return maxInFlightConnects;
    }

    public void setMaxInFlightConnects(int maxInFlightConnects) {
        this.maxInFlightConnects = maxInFlightConnects;
    }

    public Duration getConnectFailureCountWindow() {
        return connectFailureCountWindow;
    }

    public void setConnectFailureCountWindow(Duration connectFailureCountWindow) {
        this.connectFailureCountWindow = connectFailureCountWindow;
    }

    public int getConnectFailurePreemptThreshold() {
        return connectFailurePreemptThreshold;
    }

    public void setConnectFailurePreemptThreshold(int connectFailurePreemptThreshold) {
        this.connectFailurePreemptThreshold = connectFailurePreemptThreshold;
    }

    public Duration getPreemptHoldDown() {
        return preemptHoldDown;
    }

    public void setPreemptHoldDown(Duration preemptHoldDown) {
        this.preemptHoldDown = preemptHoldDown;
    }

    public static Configuration fromEnv() {
        var config = new Configuration();
        config.setAppBaseUrl(Env.required("APP_BASE_URL"));
        config.setAppwardDispatcherQueueSize(Env.intVar("APPWARD_DISPATCHER_QUEUE_SIZE", 1024));
        return config;
    }
}
