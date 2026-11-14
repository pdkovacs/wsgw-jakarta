package io.github.pdkovacs.wsgw.e2e.driver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pdkovacs.wsgw.e2e.common.dto.E2EMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class Message {
    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    public String getText() {
        return text;
    }

    public static E2EMessage parse(String msgStr) throws JsonProcessingException {
        return MAPPER.readValue(msgStr, E2EMessage.class);
    }

}
