package io.github.pdkovacs.wsgw.appward;

import java.io.IOException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.github.pdkovacs.wsgw.AppPaths;
import io.github.pdkovacs.wsgw.appward.Dispatcher.Dispatch;
import io.github.pdkovacs.wsgw.appward.Dispatcher.EnqueueStatus;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;

public class Relay {
    private static final CtxLogger logger = CtxLogger.of(Relay.class);

    public record RetryParams(int maxRetries, Duration retryInterval, RetryBudget retryBudget) {
    }

    // The retry/exhaustion/budget-drop counters are registered ahead of the retry loop (docs/backpressure.md
    // §2.5.2, [planned]): nothing increments them yet.
    record Meters(Timer enqueueWaitTime, Timer relayLatency, Counter relayAttempts,
                  Counter relayRetries, Counter enqueueTimeoutDrops, Counter retryExhaustions) {
    }

    private final Request appwardRequest;
    private final Map<String, List<String>> requestHeaders;
    private final String connectionId;
    private final Dispatcher dispatcher;
    private final Duration responseTimeout;
    private final RetryParams retryParams;
    private final Meters meters;

    Relay(Request appwardRequest,
          Map<String, List<String>> requestHeaders,
          String connectionId,
          Dispatcher.QueueParams queueParams,
          Duration responseTimeout,
          RetryParams retryParams,
          Meters meters,
          Runnable done) {
        this.appwardRequest = appwardRequest;
        this.requestHeaders = requestHeaders;
        this.connectionId = connectionId;
        this.responseTimeout = responseTimeout;
        this.retryParams = retryParams;
        dispatcher = new Dispatcher(queueParams, error -> logger.error("Error sending message", error), done);
        dispatcher.start(connectionId);
        this.meters = meters;
    }

    public void sendMessage(String msg) {
        dispatcherAccept(() -> relayToApp(AppPaths.MESSAGE_FROM_WSGW, msg));
    }

    public void sendDisconnect() {
        var mLogger = logger.with("connectionId", connectionId).with("method", "sendDisconnect");
        mLogger.debug("sendDisconnect called");

        Thread.ofVirtual().start(() -> {
            try {
                dispatcher.blockUntilAccepted(() -> {
                    relayToApp(AppPaths.DISCONNECTED_FROM_WSGW, null);
                });
                dispatcher.blockUntilAccepted(Dispatcher.POISON);
            } catch (InterruptedException e) {
                mLogger.error("Interrupted", e);
            } catch (Throwable t) {
                mLogger.error("Error enqueueing message", t);
            }
        });
    }

    public void join(Duration timeout) {
        dispatcher.join(timeout);
    }

    private void dispatcherAccept(Dispatch dispatch) {
        var mLogger = logger.with("connectionId", connectionId).with("method", "dispatcherAccept");
        var startTime = System.nanoTime();
        var enqueueStatus = dispatcher.accept(dispatch);
        mLogger.debug("enqueueStatus: {}", enqueueStatus);
        if (enqueueStatus.equals(EnqueueStatus.ENQUEUED_AFTER_WAIT) || enqueueStatus.equals(EnqueueStatus.DROPPED)) {
            meters.enqueueWaitTime.record(Duration.ofNanos(System.nanoTime() - startTime));
        }
        if (enqueueStatus.equals(EnqueueStatus.DROPPED)) {
            meters.enqueueTimeoutDrops.increment();
        }
    }

    private void relayToApp(String pathOnApp, String msg) {
        var log = logger.with("path", pathOnApp).with("connId", connectionId);
        try {
            var retries = new Retries(retryParams, meters);
            do {
                var startTime = System.nanoTime();
                log.debug("retry count: {}", retries.retryCount);
                try {
                    log.debug("Sending request to app...");
                    meters.relayAttempts.increment();
                    appwardRequest.send(requestHeaders, pathOnApp + "/" + connectionId, "POST",
                            msg, responseTimeout);
                    meters.relayLatency.record(Duration.ofNanos(System.nanoTime() - startTime));
                    log.debug("Request sent to app");
                    break;
                } catch (HttpConnectTimeoutException connectTimeoutException) {
                    // Never reached the app: reachability, not latency (§2.5.2).
                    throw connectTimeoutException;
                } catch (HttpTimeoutException e) {
                    log.debug("Request timed out");
                    // A relay attempt that ends at the timeout is recorded alongside one the app answered (§2.5.2).
                    meters.relayLatency.record(Duration.ofNanos(System.nanoTime() - startTime));
                }
            } while (retries.tryReserveRetry());
        } catch (InterruptedException e) {
            log.warn("Interrupted while waiting for request to connect");
            throw new RuntimeException(e);
        } catch (HttpTimeoutException e) {
            log.warn("Timeout while waiting for the app to accept the relay", e);
            throw new RuntimeException(e);
        } catch (IOException e) {
            log.warn("IOException while waiting for request to connect", e);
            throw new RuntimeException(e);
        } catch (Exception e) {
            log.error("Exception while waiting for request to connect", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public String toString() {
        return "Relay{" +
                "appwardRequest='" + appwardRequest + '\'' +
                ", connectionId='" + connectionId + '\'' +
                ", dispatcher=" + dispatcher +
                '}';
    }

    int queueSize() {
        return dispatcher.queueSize();
    }

    private static class Retries {

        private final RetryParams retryParams;
        private final Meters retryMeters;
        private int retryCount = 0;

        Retries(RetryParams retryParams, Meters retryMeters) {
            this.retryParams = retryParams;
            this.retryMeters = retryMeters;
        }

        boolean tryReserveRetry() throws InterruptedException {
            if (retryCount >= retryParams.maxRetries) {
                retryMeters.retryExhaustions.increment();
                return false;
            }

            if (!retryParams.retryBudget.tryReserveRetry()) {
                return false;
            }

            Thread.sleep(retryParams.retryInterval);

            retryCount++;
            return true;
        }
    }
}
