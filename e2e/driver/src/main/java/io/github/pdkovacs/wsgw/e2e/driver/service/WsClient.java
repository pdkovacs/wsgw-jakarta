package io.github.pdkovacs.wsgw.e2e.driver.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.*;

import io.github.pdkovacs.wsgw.WsgwPaths;
import io.github.pdkovacs.wsgw.logging.CtxLogger;

public class WsClient {

    private static final CtxLogger logger = CtxLogger.of(WsClient.class);

    private static final long CLOSE_TIMEOUT_SECONDS = 5;

    private final HttpClient httpClient;
    private final String wsgwServer;
    private final BlockingQueue<String> msgFromApp;
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private final ConcurrentHashMap<JakartaWebSocket, StringBuilder> textChunkCache = new ConcurrentHashMap<>();

    private WebSocket webSocket;
    private CtxLogger sessionLogger;

    public WsClient(HttpClient httpClient, String wsgwServer, BlockingQueue<String> msgFromApp) {
        this.httpClient = httpClient;
        this.wsgwServer = wsgwServer;
        this.msgFromApp = msgFromApp;
    }

    CompletableFuture<WebSocket> open(String runId, String user, String password, Duration connectTimeout) {
        sessionLogger = logger.with("user", user).with("testRunId", runId);
        String userAndPassword = user + ":" + password;
        var authHeaderValue = "Basic " + Base64.getEncoder().encodeToString(userAndPassword.getBytes(StandardCharsets.UTF_8));
        return httpClient.newWebSocketBuilder()
                .header("Authorization", authHeaderValue)
                .connectTimeout(connectTimeout)
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

                    @Override
                    public CompletionStage<Void> onText(WebSocket webSocket,
                                                        CharSequence data,
                                                        boolean last) {
                        var future = new CompletableFuture<Void>();
                        Thread.ofVirtual().start(() -> {
                            var lookupKey = new JakartaWebSocket(webSocket);
                            var soFar = textChunkCache.computeIfAbsent(lookupKey, k -> new StringBuilder());
                            soFar.append(data);
                            if (last) {
                                try {
                                    msgFromApp.put(soFar.toString());
                                    soFar.setLength(0);
                                } catch (InterruptedException e) {
                                    logger.warn("Interrupted while waiting for message {} from websocket", data, e);
                                    throw new RuntimeException(e);
                                }
                            }
                            webSocket.request(1);
                            future.complete(null);
                        });
                        return future;
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
     * Call it only after {@link #open}'s future has completed.
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

    record JakartaWebSocket(WebSocket webSocket) {
        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            JakartaWebSocket that = (JakartaWebSocket) o;
            return webSocket == that.webSocket;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(webSocket);
        }
    };
}
