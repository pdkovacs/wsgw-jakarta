package io.github.pdkovacs.wsgw;

import jakarta.websocket.*;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class TestClientEndpoint extends jakarta.websocket.Endpoint{
    final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

    Session session;

    @Override
    public void onOpen(Session session, EndpointConfig endpointConfig) {
        this.session = session;
        this.session.addMessageHandler(new MessageHandler.Whole<String>() {
            @Override
            public void onMessage(String message) {
                messages.add(message);
            }
        });
    }
}


