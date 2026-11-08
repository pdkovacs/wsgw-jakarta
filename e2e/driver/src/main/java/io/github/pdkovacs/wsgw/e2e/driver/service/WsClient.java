package io.github.pdkovacs.wsgw.e2e.driver.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.github.pdkovacs.wsgw.WsgwPaths;
import io.github.pdkovacs.wsgw.logging.CtxLogger;

public class WsClient {

    private static final CtxLogger logger = CtxLogger.of(WsClient.class);

    private static final long CLOSE_TIMEOUT_SECONDS = 5;

    private final HttpClient httpClient;
    private final String wsgwServer;
    private final BlockingQueue<String> msgFromApp;
    private final CompletableFuture<Void> closed = new CompletableFuture<>();

    private WebSocket webSocket;
    private CtxLogger sessionLogger;

    public WsClient(HttpClient httpClient, String wsgwServer, BlockingQueue<String> msgFromApp) {
        this.httpClient = httpClient;
        this.wsgwServer = wsgwServer;
        this.msgFromApp = msgFromApp;
    }

    CompletableFuture<WebSocket> connect(String runId, String user, String password) {
        sessionLogger = logger.with("user", user).with("testRunId", runId);
        String userAndPassword = user + ":" + password;
        var authHeaderValue = "Basic " + Base64.getEncoder().encodeToString(userAndPassword.getBytes(StandardCharsets.UTF_8));
        return httpClient.newWebSocketBuilder()
                .header("Authorization", authHeaderValue)
                .buildAsync(URI.create("ws://%s%s".formatted(wsgwServer, WsgwPaths.CONNECT_FROM_CLIENT)), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        boolean closeSent = webSocket.isOutputClosed();
                        var log = sessionLogger.with("statusCode", statusCode).with("reason", reason).with("closeSent", closeSent);
                        if (closeSent && statusCode != 1006) {
                            log.debug("Session closed");
                        } else {
                            log.warn("Session closed by the gateway or lost");
                        }
                        closed.complete(null);
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        sessionLogger.error("Session error", error);
                        closed.completeExceptionally(error);
                    }
                })
                .thenApply(openedWebSocket -> {
                    webSocket = openedWebSocket;
                    return openedWebSocket;
                });
    }

    /**
     * Closes the session, waiting for the gateway's close frame, then drops the TCP connection in any
     * case: {@link HttpClient#close()} would otherwise block on a session the gateway never closes.
     * Call it only after {@link #connect}'s future has completed.
     */
    void close() throws ExecutionException, InterruptedException, TimeoutException {
        try {
            if (closed.isDone()) {
                sessionLogger.debug("Session has already been closed");
                return;
            }

            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            closed.get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            webSocket.abort();
        }
    }
}
