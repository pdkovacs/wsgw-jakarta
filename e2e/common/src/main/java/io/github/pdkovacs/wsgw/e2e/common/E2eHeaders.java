package io.github.pdkovacs.wsgw.e2e.common;

/**
 * HTTP headers the e2e driver and apps agree on.
 */
public class E2eHeaders {
    /**
     * Sent by the driver on the WebSocket upgrade request. The gateway forwards the upgrade
     * request's headers to the app on connect, and again on disconnect.
     */
    public static final String RUN_ID = "X-Run-Id";
}
