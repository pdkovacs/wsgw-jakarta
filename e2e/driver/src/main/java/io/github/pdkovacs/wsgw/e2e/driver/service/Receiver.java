package io.github.pdkovacs.wsgw.e2e.driver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.pdkovacs.wsgw.e2e.common.auth.PasswordCredentials;
import io.github.pdkovacs.wsgw.e2e.driver.Monitoring;
import io.github.pdkovacs.wsgw.logging.CtxLogger;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;

/**
 * A test user's connection to the gateway. It only receives: creating and sending the messages is
 * {@link TestRun}'s business.
 */
public class Receiver implements Runnable {
    private static final CtxLogger logger = CtxLogger.of(Receiver.class);

    private static final long CONNECT_TIMEOUT_SECONDS = 30;

    private final BlockingQueue<String> msgFromApp = new LinkedBlockingQueue<>();
    private final PasswordCredentials credentials;
    private final WsClient wsClient;
    private final DeliveryTracker outstandingMessages;
    private final Monitoring monitoring;
    private final String runId;
    private final CtxLogger iLogger;

    public Receiver(HttpClient httpClient, String wsgwServer,
                    PasswordCredentials credentials,
                    DeliveryTracker outstandingMessages, Monitoring monitoring, String runId) {
        this.credentials = credentials;
        this.wsClient = new WsClient(httpClient, wsgwServer, msgFromApp);
        this.outstandingMessages = outstandingMessages;
        this.monitoring = monitoring;
        this.runId = runId;
        iLogger = logger.with("runId", runId);
    }

    void connectAndListen() throws ExecutionException, InterruptedException {
        var opening = wsClient.open(runId, credentials.username(), credentials.password(), Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS));
        opening.get();
        Thread.ofVirtual().start(this);
    }

    /**
     * Must only be called after {@link #connectAndListen()} has succeeded.
     */
    void closeSession() throws ExecutionException, InterruptedException, TimeoutException {
        wsClient.close();
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                processMessageFromApp(msgFromApp.take());
            } catch (InterruptedException e) {
                iLogger.warn("Received interrupt while waiting for message from app", e);
            }
        }
    }

    private void processMessageFromApp(String message) {
        Instant receivedAt = Instant.now();

        var mLogger = iLogger.with("method", "processMessageFromApp");

        try {
            var msgFromApp = Message.parse(message);
            mLogger = mLogger.with("msgId", msgFromApp.id()).with("destination", msgFromApp.destination());

            var msgInRepo = outstandingMessages.getMessage(msgFromApp.id());
            if (msgInRepo == null) {
                mLogger.error("incoming message not found amongst outstanding");
                monitoring.incOutstandingMsgNotFoundCount().run();
                return;
            }

            var sentAt = msgFromApp.getSentAt();
            var deliveryDuration = Duration.between(sentAt, receivedAt);

            if (!msgFromApp.data().equals(msgInRepo.getText())) {
                monitoring.incMsgTextMismatchCounter().run();
            }

            if (deliveryDuration.getNano() > 400) {
                mLogger.with("deliveryDuration", deliveryDuration).debug("extra slow");
            }

            outstandingMessages.markDelivered(msgFromApp.id(), credentials.username());

            monitoring.recordDeliveryDurationMs().accept(deliveryDuration);
        } catch (JsonProcessingException e) {
            mLogger.error("Error parsing message", e);
        }
    }
}
