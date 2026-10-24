package io.github.pdkovacs.wsgw.e2e.driver;

import io.github.pdkovacs.wsgw.logging.CtxLogger;

import java.util.UUID;

public class TestRun implements Runnable {

    private static final CtxLogger logger = CtxLogger.of(TestRun.class);

    private final String testRunId;
    private final Runnable completionCallback;

    private TestRun(String testRunId, Runnable completionCallback) {
        this.testRunId = testRunId;
        this.completionCallback = completionCallback;
    }

    public String getTestRunId() {
        return testRunId;
    }

    public void run() {
        try {
            createConnectRunClients();
        } finally {
            if (completionCallback != null) completionCallback.run();
        }
    }

    public void start() {
        try {
            Thread.ofVirtual().start(this);
        } catch (Throwable t) {
            completionCallback.run();
            logger.error("Test run failed", t);
            throw new RuntimeException(t);
        }
    }

    public static TestRun create(Runnable completionCallback) {
        return new TestRun(UUID.randomUUID().toString(), completionCallback);
    }

    private void createConnectRunClients() {
    }

}
