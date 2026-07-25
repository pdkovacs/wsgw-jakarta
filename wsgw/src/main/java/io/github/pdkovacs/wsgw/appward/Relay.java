package io.github.pdkovacs.wsgw.appward;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import io.github.pdkovacs.wsgw.AppPaths;
import io.github.pdkovacs.wsgw.logging.CtxLogger;

public class Relay {
    private static final CtxLogger logger = CtxLogger.of(Relay.class);

    private final String appBaseUrl;

    public Relay(String appBaseUrl) {
        this.appBaseUrl = appBaseUrl;
    }

    public void sendMessage(Map<String, List<String>> connectHeaders, String connectionId, String msg) {
        relayToApp(connectHeaders, connectionId, AppPaths.MESSAGE_FROM_WSGW, msg);
    }

    public void sendDisconnect(Map<String, List<String>> connectHeaders, String connectionId) {
        logger.debug("ToAppRelay.onDisconnect");
        relayToApp(connectHeaders, connectionId, AppPaths.DISCONNECTED_FROM_WSGW, null);
    }

    private void relayToApp(Map<String, List<String>> connectHeaders, String connectionId, String pathOnApp,
            String msg) {
        var log = logger.with("path", pathOnApp).with("connId", connectionId);
        try {
            log.debug("Sending request to app...");
            Request.send(appBaseUrl, connectHeaders, pathOnApp + "/" + connectionId, "POST",
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