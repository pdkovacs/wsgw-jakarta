package io.github.pdkovacs.wsgw.fake.app;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class WsgwConnectionEndpoint {
    final BlockingQueue<String> messages =  new LinkedBlockingQueue<>();
    final String connectionId;

    public WsgwConnectionEndpoint(String connectionId) {
        this.connectionId = connectionId;
    }

    public BlockingQueue<String> getMessages() {
        return messages;
    }
}
