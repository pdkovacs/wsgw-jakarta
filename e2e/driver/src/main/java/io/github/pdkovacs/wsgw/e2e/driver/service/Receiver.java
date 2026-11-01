package io.github.pdkovacs.wsgw.e2e.driver.service;

import io.github.pdkovacs.wsgw.e2e.common.auth.PasswordCredentials;
import io.github.pdkovacs.wsgw.e2e.driver.Monitoring;

import java.net.http.HttpClient;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

/**
 * A test user's connection to the gateway. It only receives: creating and sending the messages is
 * {@link TestRun}'s business.
 */
public class Receiver {
    private final PasswordCredentials credentials;
    private final WsClient wsClient;
    private final DeliveryTracker outstandingMessages;
    private final Monitoring monitoring;
    private final String runId;

    public Receiver(HttpClient httpClient, String wsgwUri, BlockingQueue<String> msgFromApp,
                    PasswordCredentials credentials,
                    DeliveryTracker outstandingMessages, Monitoring monitoring, String runId) {
        this.credentials = credentials;
        this.wsClient = new WsClient(httpClient, wsgwUri, msgFromApp);
        this.outstandingMessages = outstandingMessages;
        this.monitoring = monitoring;
        this.runId = runId;
    }

    void connectAndListen(CountDownLatch latch) throws ExecutionException, InterruptedException {
        wsClient.connect(runId, credentials.username(), credentials.password())
                .get();
    }
}
