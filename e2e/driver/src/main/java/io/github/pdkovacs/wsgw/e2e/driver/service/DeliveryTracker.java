package io.github.pdkovacs.wsgw.e2e.driver.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DeliveryTracker {
    private final Map<String, Message> pendingDeliveries = new HashMap<>();

    private Runnable notifyEmpty;

    public DeliveryTracker() {}

    /**
     * Must be called in sequence strictly before watchDraining() is called
     * @param delivery
     */
    public void addDelivery(Message delivery) {

    }

    public synchronized void watchDraining(Runnable notifyEmpty) {
        if (pendingDeliveries.size() == 0) {
            notifyEmpty.run();
        }
        this.notifyEmpty = notifyEmpty;
    }

    public synchronized Message getMessage(String id) {
        return pendingDeliveries.get(id);
    }

    /**
     * Returns the message, if it was delivered to all recipients
     */
    public synchronized Message markDelivered(String id, String recipientName) {
        var msg = pendingDeliveries.get(id);
        if (msg == null) {
            return null;
        }

        return msg.setRecipientServed(recipientName) == 0
                ? msg
                : null;
    }

    /**
     * Drains the pending deliveries and returns them
     */
    public synchronized List<Message> drainPending() {
        List <Message> messages = new ArrayList<>();

        for (var msg : pendingDeliveries.values()) {
            var removed = pendingDeliveries.remove(msg.getId());
            if (removed != null) {
                messages.add(removed);
            }
        }

        return messages;
    }
}
