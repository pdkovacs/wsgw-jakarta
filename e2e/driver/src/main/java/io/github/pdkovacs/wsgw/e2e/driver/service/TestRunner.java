package io.github.pdkovacs.wsgw.e2e.driver.service;

import io.github.pdkovacs.wsgw.e2e.common.auth.PasswordCredentials;
import io.github.pdkovacs.wsgw.e2e.driver.Monitoring;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;

public class TestRunner {
    private final HttpClient httpClient;
    private final String wsgwServer;
    private final URI appServiceUrl;
    private final List<PasswordCredentials> allCredentials;
    private final PasswordCredentials senderCredentials;
    private final Monitoring monitoring;

    private TestRun testRun;

    public TestRunner(HttpClient httpClient, String wsgwServer, URI appServiceUrl, List<PasswordCredentials> allCredentials,
                      PasswordCredentials senderCredentials, Monitoring monitoring) {
        this.httpClient = httpClient;
        this.wsgwServer = wsgwServer;
        this.appServiceUrl = appServiceUrl;
        this.allCredentials = allCredentials;
        this.senderCredentials = senderCredentials;
        this.monitoring = monitoring;
    }

    /**
     * Returns as soon as the run has started: its outcome is not known yet.
     *
     * @throws IllegalArgumentException if either count is out of range
     */
    public synchronized String createStartTestRun(PerRunParams params) {
        if (testRun != null) {
            throw new IllegalStateException("A test is in progress");
        }

        Runnable onDone = () -> {
            synchronized (TestRunner.this) {
                testRun = null;
            }
        };
        testRun = TestRun.create(httpClient, wsgwServer, appServiceUrl, params,
                allCredentials, senderCredentials, onDone, monitoring);
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
