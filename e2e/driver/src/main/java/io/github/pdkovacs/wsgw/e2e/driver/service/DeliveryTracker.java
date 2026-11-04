package io.github.pdkovacs.wsgw.e2e.driver.service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class DeliveryTracker {
    private final Map<String, Message> pendingDeliveries = new HashMap<>();
    private final AtomicInteger failedCount = new AtomicInteger(0);

    record Result(int failed, int deliveryPending) {
    }

    private CompletableFuture<Result> allDeliveredPromise;


    /**
     * Must be called in sequence strictly before allDelivered() is called
     * @param delivery
     */
    public void addDelivery(Message delivery) {
        if (allDeliveredPromise != null) {
            throw new IllegalStateException("addDelivery must be called before allDelivered");
        }
        pendingDeliveries.put(delivery.getId(), delivery);
    }

    synchronized CompletableFuture<Result> allDelivered() throws InterruptedException {
        allDeliveredPromise = new CompletableFuture<>();
        return allDeliveredPromise;
    }

    public synchronized Message getMessage(String id) {
        return pendingDeliveries.get(id);
    }

    public synchronized void markDelivered(String id, String recipientName) {
        registerDeliveryResult(id, recipientName, true);
    }

    private void registerDeliveryResult(String id, String recipientName, boolean successful) {
        if (allDeliveredPromise == null) {
            throw new IllegalStateException("markDelivered must not be called before allDelivered");
        }

        var msg = pendingDeliveries.get(id);

        if (!successful) {
            failedCount.incrementAndGet();
        }

        if (msg == null) {
            return;
        }

        if (msg.setRecipientServed(recipientName) == 0) {
            pendingDeliveries.remove(id);
            if (pendingDeliveries.isEmpty()) {
                allDeliveredPromise.complete(getResult());
            }
        }
    }

    public synchronized void markFailed(String id, String recipientName) {
        registerDeliveryResult(id, recipientName, false);
    }

    /**
     * Drains the pending deliveries and returns them
     */
    synchronized Result getResult() {
        return new Result(failedCount.get(), pendingDeliveries.size());
    }
}
