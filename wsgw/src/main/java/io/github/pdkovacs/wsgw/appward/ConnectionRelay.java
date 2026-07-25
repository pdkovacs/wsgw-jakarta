package io.github.pdkovacs.wsgw.appward;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import io.github.pdkovacs.wsgw.AppPaths;
import io.github.pdkovacs.wsgw.logging.CtxLogger;

public class ConnectionRelay {
    private static final CtxLogger logger = CtxLogger.of(ConnectionRelay.class);

    private final String appBaseUrl;
    private final Map<String, List<String>> requestHeaders;
    private final String connectionId;

    ConnectionRelay(String appBaseUrl, Map<String, List<String>> requestHeaders, String connectionId) {
        this.appBaseUrl = appBaseUrl;
        this.requestHeaders = requestHeaders;
        this.connectionId = connectionId;
        // create message processor with a bounded queue
    }

    void close() {
        // close message processor with a bounded queue
    }

    public void sendMessage(String msg) {
        relayToApp(AppPaths.MESSAGE_FROM_WSGW, msg);
    }

    public void sendDisconnect() {
        logger.debug("ToAppRelay.onDisconnect");
        relayToApp(AppPaths.DISCONNECTED_FROM_WSGW, null);
    }

    private void relayToApp(String pathOnApp, String msg) {
        var log = logger.with("path", pathOnApp).with("connId", connectionId);
        try {
            log.debug("Sending request to app...");
            Request.send(appBaseUrl, requestHeaders, pathOnApp + "/" + connectionId, "POST",
                    msg);
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

}