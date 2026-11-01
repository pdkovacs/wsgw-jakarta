package io.github.pdkovacs.wsgw.e2e.driver.service;

import io.github.pdkovacs.wsgw.e2e.common.auth.PasswordCredentials;
import io.github.pdkovacs.wsgw.e2e.driver.Monitoring;

import java.net.http.HttpClient;
import java.util.List;

public class TestRunner {
    private final HttpClient httpClient;
    private final List<PasswordCredentials> allCredentials;
    private final PasswordCredentials senderCredentials;
    private final Monitoring monitoring;

    public TestRunner(HttpClient httpClient, List<PasswordCredentials> allCredentials,
                      PasswordCredentials senderCredentials, Monitoring monitoring) {
        this.httpClient = httpClient;
        this.allCredentials = allCredentials;
        this.senderCredentials = senderCredentials;
        this.monitoring = monitoring;
    }

    /**
     * @throws IllegalArgumentException if either count is out of range
     */
    public String createStartTestRun(int userCount, int messageCount) {
        return TestRun.create(httpClient, userCount, messageCount, allCredentials, senderCredentials, monitoring)
                .getTestRunId();
    }
}
