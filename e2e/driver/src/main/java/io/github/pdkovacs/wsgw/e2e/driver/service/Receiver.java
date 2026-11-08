package io.github.pdkovacs.wsgw.e2e.driver.service;

import io.github.pdkovacs.wsgw.e2e.common.auth.PasswordCredentials;
import io.github.pdkovacs.wsgw.e2e.driver.Monitoring;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * A test user's connection to the gateway. It only receives: creating and sending the messages is
 * {@link TestRun}'s business.
 */
public class Receiver {
    private static final long CONNECT_TIMEOUT_SECONDS = 30;

    private final PasswordCredentials credentials;
    private final WsClient wsClient;
    private final DeliveryTracker outstandingMessages;
    private final Monitoring monitoring;
    private final String runId;

    public Receiver(HttpClient httpClient, String wsgwServer, BlockingQueue<String> msgFromApp,
                    PasswordCredentials credentials,
                    DeliveryTracker outstandingMessages, Monitoring monitoring, String runId) {
        this.credentials = credentials;
        this.wsClient = new WsClient(httpClient, wsgwServer, msgFromApp);
        this.outstandingMessages = outstandingMessages;
        this.monitoring = monitoring;
        this.runId = runId;
    }

    void connectAndListen() throws ExecutionException, InterruptedException {
        var opening = wsClient.open(runId, credentials.username(), credentials.password(), Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS));
        opening.get();
    }

    /**
     * Must only be called after {@link #connectAndListen()} has succeeded.
     */
    void closeSession() throws ExecutionException, InterruptedException, TimeoutException {
        wsClient.close();
    }
}
