package io.github.pdkovacs.wsgw.e2e.driver.service;

import io.github.pdkovacs.wsgw.e2e.common.auth.PasswordCredentials;
import io.github.pdkovacs.wsgw.e2e.driver.Monitoring;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class TestRunner {
    private final HttpClient httpClient;
    private final String wsgwServer;
    private final List<PasswordCredentials> allCredentials;
    private final PasswordCredentials senderCredentials;
    private final Monitoring monitoring;

    private TestRun testRun;

    public TestRunner(HttpClient httpClient, String wsgwServer, List<PasswordCredentials> allCredentials,
                      PasswordCredentials senderCredentials, Monitoring monitoring) {
        this.httpClient = httpClient;
        this.wsgwServer = wsgwServer;
        this.allCredentials = allCredentials;
        this.senderCredentials = senderCredentials;
        this.monitoring = monitoring;
    }

    /**
     * Returns as soon as the run has started: its outcome is not known yet.
     *
     * @throws IllegalArgumentException if either count is out of range
     */
    public synchronized String createStartTestRun(int userCount, int messageCount, Duration timeout) {
        if (testRun != null) {
            throw new IllegalStateException("A test is in progress");
        }

        testRun = TestRun.create(httpClient, wsgwServer, userCount, messageCount, timeout,
                allCredentials, senderCredentials, monitoring);
        testRun.start();
        return testRun.getTestRunId();
    }

    public synchronized void stopTestRun(String testRunId) {
        if (testRun == null) {
            throw new IllegalStateException("No test is in progress");
        }

        if (!testRun.getTestRunId().equals(testRunId)) {
            throw new IllegalArgumentException("Test is in progress, but test run id does not match test run id");
        }

        testRun.stop();
    }
}
