package io.github.pdkovacs.wsgw.e2e.driver.service;

import io.github.pdkovacs.wsgw.e2e.common.auth.PasswordCredentials;
import io.github.pdkovacs.wsgw.logging.CtxLogger;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TestRun implements Runnable {

    private static final CtxLogger logger = CtxLogger.of(TestRun.class);

    private final HttpClient httpClient;
    private final int testUserCount;
    private final String testRunId;
    private final List<PasswordCredentials> allCredentials;

    private Map<String, Client> map;

    private TestRun(HttpClient httpClient, int testUserCount, String testRunId,
                    List<PasswordCredentials> allCredentials) {
        this.httpClient = httpClient;
        this.testUserCount = testUserCount;
        this.testRunId = testRunId;
        this.allCredentials = allCredentials;
    }

    public String getTestRunId() {
        return testRunId;
    }

    public void run() {
        try {
            map = createConnectRunClients();
        } catch (Exception e) {
            logger.error("Exception occurred while connecting to test run", e);
        } finally {
            logger.info("Test run finished" + testRunId);
        }
    }

    public void start() {
        try {
            Thread.ofVirtual().start(this);
        } catch (Throwable t) {
            logger.error("Test run failed", t);
            throw new RuntimeException(t);
        }
    }

    public static TestRun create(HttpClient httpClient, int userCount, List<PasswordCredentials> allCredentials) {
        return new TestRun(httpClient, userCount, UUID.randomUUID().toString(), allCredentials);
    }

    private Map<String, Client> createConnectRunClients() {
        return null;
    }

}
