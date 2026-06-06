package io.github.pdkovacs.wsgw;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;

import java.util.concurrent.CountDownLatch;

@ClientEndpoint
public class TestClient {
    final CountDownLatch opened = new CountDownLatch(1);
    @OnOpen
    public void onOpen(Session s) { opened.countDown(); }
    @OnMessage
    public void onMessage(String msg) { /* ... */ }
}


