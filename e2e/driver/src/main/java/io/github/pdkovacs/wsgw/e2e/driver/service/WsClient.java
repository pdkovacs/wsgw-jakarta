package io.github.pdkovacs.wsgw.e2e.driver.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;

import io.github.pdkovacs.wsgw.WsgwPaths;

public class WsClient {

    private final HttpClient httpClient;
    private final String wsgwUri;
    private final BlockingQueue<String> msgFromApp;

    private WebSocket webSocket;

    public WsClient(HttpClient httpClient, String wsgwUri, BlockingQueue<String> msgFromApp) {
        this.httpClient = httpClient;
        this.wsgwUri = wsgwUri;
        this.msgFromApp = msgFromApp;
    }

    CompletableFuture<WebSocket> connect(String runId, String user, String password) {
        String userAndPassword = user + ":" + password;
        var authHeaderValue = "Basic " + Base64.getEncoder().encodeToString(userAndPassword.getBytes(StandardCharsets.UTF_8));
        return httpClient.newWebSocketBuilder()
                .header("Authorization", authHeaderValue)
                .buildAsync(URI.create("ws://%s%s".formatted(wsgwUri, WsgwPaths.CONNECT_FROM_CLIENT)), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        WebSocket.Listener.super.onOpen(webSocket);
                        WsClient.this.webSocket = webSocket;
                    }
                });
    }
}
