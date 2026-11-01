package io.github.pdkovacs.wsgw.e2e.driver.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class Message {
    private final String testRunId;
    private final String id;
    private final String text;
    private final List<String> recipientsWaiting;
    private final Instant sentAt = Instant.now();


    public Message(String testRunId, String id, String text, List<String> recipients) {
        this.testRunId = testRunId;
        this.id = id;
        this.text = text;
        // A private copy: messages of a run can be handed the same list, and this one gets consumed
        this.recipientsWaiting = new ArrayList<>(recipients);
    }

    /**
     * Should be called only {@link DeliveryTracker#markDelivered(String, String)}
     */
    public int setRecipientServed(String recipientName) {
        recipientsWaiting.remove(recipientName);
        return recipientsWaiting.size();
    }

    public String getId() {
        return id;
    }

    List<String> getRecipientsWaiting() {
        return List.copyOf(recipientsWaiting);
    }
}
