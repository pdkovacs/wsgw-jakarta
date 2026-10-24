package io.github.pdkovacs.wsgw.e2e.driver.service;

import io.github.pdkovacs.wsgw.e2e.common.auth.PasswordCredentials;

import java.net.http.HttpClient;
import java.util.List;

public class TestRunner {
    private final HttpClient httpClient;
    private final List<PasswordCredentials> allCredentials;

    public TestRunner(HttpClient httpClient, List<PasswordCredentials> allCredentials) {
        this.httpClient = httpClient;
        this.allCredentials = allCredentials;
    }

    public String createStartTestRun(int userCount) {
        return TestRun.create(httpClient, userCount, allCredentials).getTestRunId();
    }
}
