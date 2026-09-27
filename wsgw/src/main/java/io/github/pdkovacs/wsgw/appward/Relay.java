package io.github.pdkovacs.wsgw.appward;

import java.io.IOException;
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

    record Meters(Timer enqueueWaitTime, Counter relayDropCount) {}

    private final Request appwardRequest;
    private final Map<String, List<String>> requestHeaders;
    private final String connectionId;
    private final Dispatcher dispatcher;
    private final Meters meters;

    Relay(Request appwardRequest,
          Map<String, List<String>> requestHeaders,
          String connectionId,
          Dispatcher.QueueParams queueParams,
          Meters meters,
          Runnable done) {
        this.appwardRequest = appwardRequest;
        this.requestHeaders = requestHeaders;
        this.connectionId = connectionId;
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
            } catch(Throwable t) {
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
        if  (enqueueStatus.equals(EnqueueStatus.ENQUEUED_AFTER_WAIT) || enqueueStatus.equals(EnqueueStatus.DROPPED)) {
            meters.enqueueWaitTime.record(Duration.ofNanos(System.nanoTime() - startTime));
        }
        if (enqueueStatus.equals(EnqueueStatus.DROPPED)) {
            meters.relayDropCount.increment();
        }
    }

    private void relayToApp(String pathOnApp, String msg) {
        var log = logger.with("path", pathOnApp).with("connId", connectionId);
        try {
            log.debug("Sending request to app...");
            appwardRequest.send(requestHeaders, pathOnApp + "/" + connectionId, "POST",
                    msg, null);
            log.debug("Request sent to app");
        } catch (InterruptedException e) {
            log.warn("Interrupted while waiting for request to connect");
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
}
