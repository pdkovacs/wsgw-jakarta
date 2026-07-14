package io.github.pdkovacs.wsgw.integration.app.fake;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import io.github.pdkovacs.wsgw.integration.Message;

public class WsgwEndpoint {
    final BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
    final String connectionId;

    public WsgwEndpoint(String connectionId) {
        this.connectionId = connectionId;
    }

    public BlockingQueue<Message> getMessageInbox() {
        return messages;
    }
}
