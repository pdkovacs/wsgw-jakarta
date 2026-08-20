package io.github.pdkovacs.wsgw.appward;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import io.github.pdkovacs.wsgw.AppPaths;
import io.github.pdkovacs.wsgw.logging.CtxLogger;

public class Relay {
    private static final CtxLogger logger = CtxLogger.of(Relay.class);

    private final Request appwardRequest;
    private final Map<String, List<String>> requestHeaders;
    private final String connectionId;
    private final Dispatcher dispatcher;

    Relay(Request appwardRequest, Map<String, List<String>> requestHeaders, String connectionId, int queueSize) {
        this.appwardRequest = appwardRequest;
        this.requestHeaders = requestHeaders;
        this.connectionId = connectionId;
        dispatcher = new Dispatcher(
                queueSize,
                error -> logger.error("Error sending message", error)
        );
        dispatcher.start(connectionId);
    }

    public void sendMessage(String msg) {
        dispatcher.accept(() -> relayToApp(AppPaths.MESSAGE_FROM_WSGW, msg));
    }

    public void sendDisconnect() {
        logger.debug("sendDisconnect called");
        dispatcher.accept(() -> {
            relayToApp(AppPaths.DISCONNECTED_FROM_WSGW, null);
            dispatcher.setDone();
        });
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
}