package io.github.pdkovacs.wsgw.e2e.driver.service;

import io.github.pdkovacs.wsgw.e2e.common.auth.PasswordCredentials;

import java.time.Instant;
import java.util.List;

public class Message {
    private final String testRunId;
    private final String id;
    private final String text;
    private final PasswordCredentials sender;
    private final List<String> recipientsWaiting;
    private final Instant sentAt = Instant.now();


    public Message(String testRunId, String id, String text, PasswordCredentials sender, List<String> recipientsWaiting) {
        this.testRunId = testRunId;
        this.id = id;
        this.text = text;
        this.sender = sender;
        this.recipientsWaiting = recipientsWaiting;
    }

    /**
     * Should be called only {@link DeliveryTracker#markDelivered(String, String)}
     */
    public int recipientServed(String recipientName) {
        recipientsWaiting.remove(recipientName);
        return recipientsWaiting.size();
    }

    public String getId() {
        return id;
    }
}
