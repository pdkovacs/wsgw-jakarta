package io.github.pdkovacs.wsgw;

import java.nio.file.Path;
import java.time.Duration;

public class Configuration {

    // -----------------------------------------------------------------------------
    // --- base ---
    // -----------------------------------------------------------------------------

    // Where Tomcat keeps its scratch/work area. Without this, embedded Tomcat
    // defaults to a "tomcat.<port>" directory under the process working dir,
    // littering the source tree.
    private Path baseDir;

    private String appBaseUrl;

    // -----------------------------------------------------------------------------
    // --- registration gate, shared by the flows (docs/backpressure.md §2.2) ---
    // -----------------------------------------------------------------------------

    // (no field yet: registrationWaitTimeout is hard-coded in its getter)

    // -----------------------------------------------------------------------------
    // --- flow=push, site=gw_to_client (docs/backpressure.md §2.3.2) ---
    // -----------------------------------------------------------------------------

    private Duration sendLockTimeout = Duration.ofSeconds(10);

    private Duration sendLockTimeoutCountWindow = Duration.ofSeconds(30);

    private int sendLockTimeoutsPreemptThreshold = 3;

    private Duration sendLockTimeoutPreemptHoldDown = Duration.ofSeconds(30);

    // -----------------------------------------------------------------------------
    // --- flow=connect, site=client_to_gw (docs/backpressure.md §2.4.1) ---
    // -----------------------------------------------------------------------------

    private int maxInFlightConnects = 10000;

    private Duration defaultAdmissionHoldDown = Duration.ofSeconds(10);

    private Duration connectFailureCountWindow = Duration.ofSeconds(30);

    private int connectFailurePreemptThreshold = 30;

    private Duration connectPreemptHoldDown = Duration.ofSeconds(30);

    // -----------------------------------------------------------------------------
    // --- flow=connect, site=gw_to_app (docs/backpressure.md §2.4.2) ---
    // -----------------------------------------------------------------------------

    private Duration connectWaitTimeout = Duration.ofSeconds(10);

    // -----------------------------------------------------------------------------
    // --- flow=relay, site=client_to_gw (docs/backpressure.md §2.5.1) ---
    // -----------------------------------------------------------------------------

    private int appwardDispatcherQueueSize = 20;

    private Duration relayEnqueueTimeout = Duration.ofSeconds(40);

    // -----------------------------------------------------------------------------
    // --- flow=relay, site=gw_to_app (docs/backpressure.md §2.5.2) ---
    // -----------------------------------------------------------------------------

    private Duration relayResponseTimeout = Duration.ofSeconds(10);

    private int maxRelayRetries = 2;

    private Duration relayRetryInterval = Duration.ofMillis(500);

    // Share of recent relays that may be retries (0.1 = 10%)
    private double relayRetryBudget = 0.1;

    private Duration relayRetryBudgetWindow = Duration.ofSeconds(30);

    // -----------------------------------------------------------------------------
    // --- base ---
    // -----------------------------------------------------------------------------

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

    // -----------------------------------------------------------------------------
    // --- registration gate, shared by the flows (docs/backpressure.md §2.2) ---
    // -----------------------------------------------------------------------------

    public Duration getRegistrationWaitTimeout() {
        return Duration.ofSeconds(10);
    }

    // -----------------------------------------------------------------------------
    // --- flow=push, site=gw_to_client (docs/backpressure.md §2.3.2) ---
    // -----------------------------------------------------------------------------

    public Duration getSendLockTimeout() {
        return sendLockTimeout;
    }

    public void setSendLockTimeout(Duration sendLockTimeout) {
        this.sendLockTimeout = sendLockTimeout;
    }

    public Duration getSendLockTimeoutCountWindow() {
        return sendLockTimeoutCountWindow;
    }

    public void setSendLockTimeoutCountWindow(Duration sendLockTimeoutCountWindow) {
        this.sendLockTimeoutCountWindow = sendLockTimeoutCountWindow;
    }

    public int getSendLockTimeoutsPreemptThreshold() {
        return sendLockTimeoutsPreemptThreshold;
    }

    public void setSendLockTimeoutsPreemptThreshold(int sendLockTimeoutsPreemptThreshold) {
        this.sendLockTimeoutsPreemptThreshold = sendLockTimeoutsPreemptThreshold;
    }

    public Duration getSendLockTimeoutPreemptHoldDown() {
        return sendLockTimeoutPreemptHoldDown;
    }

    public void setSendLockTimeoutPreemptHoldDown(Duration sendLockTimeoutPreemptHoldDown) {
        this.sendLockTimeoutPreemptHoldDown = sendLockTimeoutPreemptHoldDown;
    }

    // -----------------------------------------------------------------------------
    // --- flow=connect, site=client_to_gw (docs/backpressure.md §2.4.1) ---
    // -----------------------------------------------------------------------------

    public int getMaxInFlightConnects() {
        return maxInFlightConnects;
    }

    public void setMaxInFlightConnects(int maxInFlightConnects) {
        this.maxInFlightConnects = maxInFlightConnects;
    }

    public Duration getDefaultAdmissionHoldDown() {
        return defaultAdmissionHoldDown;
    }

    public void setDefaultAdmissionHoldDown(Duration defaultAdmissionHoldDown) {
        this.defaultAdmissionHoldDown = defaultAdmissionHoldDown;
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

    public Duration getConnectPreemptHoldDown() {
        return connectPreemptHoldDown;
    }

    public void setConnectPreemptHoldDown(Duration connectPreemptHoldDown) {
        this.connectPreemptHoldDown = connectPreemptHoldDown;
    }

    // -----------------------------------------------------------------------------
    // --- flow=connect, site=gw_to_app (docs/backpressure.md §2.4.2) ---
    // -----------------------------------------------------------------------------

    public Duration getConnectWaitTimeout() {
        return connectWaitTimeout;
    }

    public void setConnectWaitTimeout(Duration connectWaitTimeout) {
        this.connectWaitTimeout = connectWaitTimeout;
    }

    // -----------------------------------------------------------------------------
    // --- flow=relay, site=client_to_gw (docs/backpressure.md §2.5.1) ---
    // -----------------------------------------------------------------------------

    public int getAppwardDispatcherQueueSize() {
        return appwardDispatcherQueueSize;
    }

    public void setAppwardDispatcherQueueSize(int appwardDispatcherQueueSize) {
        this.appwardDispatcherQueueSize = appwardDispatcherQueueSize;
    }

    public Duration getRelayEnqueueTimeout() {
        return relayEnqueueTimeout;
    }

    public void setRelayEnqueueTimeout(Duration relayEnqueueTimeout) {
        this.relayEnqueueTimeout = relayEnqueueTimeout;
    }

    // -----------------------------------------------------------------------------
    // --- flow=relay, site=gw_to_app (docs/backpressure.md §2.5.2) ---
    // -----------------------------------------------------------------------------

    public Duration getRelayResponseTimeout() {
        return relayResponseTimeout;
    }

    public void setRelayResponseTimeout(Duration relayResponseTimeout) {
        this.relayResponseTimeout = relayResponseTimeout;
    }

    public int getMaxRelayRetries() {
        return maxRelayRetries;
    }

    public void setMaxRelayRetries(int maxRelayRetries) {
        this.maxRelayRetries = maxRelayRetries;
    }

    public Duration getRelayRetryInterval() {
        return relayRetryInterval;
    }

    public void setRelayRetryInterval(Duration relayRetryInterval) {
        this.relayRetryInterval = relayRetryInterval;
    }

    public double getRelayRetryBudget() {
        return relayRetryBudget;
    }

    public void setRelayRetryBudget(double relayRetryBudget) {
        this.relayRetryBudget = relayRetryBudget;
    }

    public Duration getRelayRetryBudgetWindow() {
        return relayRetryBudgetWindow;
    }

    public void setRelayRetryBudgetWindow(Duration relayRetryBudgetWindow) {
        this.relayRetryBudgetWindow = relayRetryBudgetWindow;
    }

    // -----------------------------------------------------------------------------
    // --- construction ---
    // -----------------------------------------------------------------------------

    public static Configuration fromEnv() {
        var config = new Configuration();
        config.setAppBaseUrl(Env.required("APP_BASE_URL"));
        config.setAppwardDispatcherQueueSize(Env.intVar("APPWARD_DISPATCHER_QUEUE_SIZE", 1024));
        return config;
    }
}
