package io.github.pdkovacs.wsgw.e2e.app.service;

import io.github.pdkovacs.wsgw.e2e.app.conntrack.WsConnections;
import io.github.pdkovacs.wsgw.e2e.common.dto.E2EMessage;
import io.github.pdkovacs.wsgw.logging.CtxLogger;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Delivers messages to every connection of every recipient. Messages go out one after
 * the other; within a message, recipients are served concurrently, at most
 * {@value #MAX_CONCURRENT_RECIPIENT_SENDS} at a time.
 *
 * <p>Owns the fan-out executor: whoever creates it closes it.
 */
public class MessageSender implements AutoCloseable {

    private static final CtxLogger logger = CtxLogger.of(MessageSender.class);
    private static final int MAX_CONCURRENT_RECIPIENT_SENDS = 4;

    private final WsConnections wsConnections;
    private final WsgwClient wsgwClient;
    private final ExecutorService fanOut = Executors.newFixedThreadPool(MAX_CONCURRENT_RECIPIENT_SENDS);

    public MessageSender(WsConnections wsConnections, WsgwClient wsgwClient) {
        this.wsConnections = wsConnections;
        this.wsgwClient = wsgwClient;
    }

    public void sendAll(List<E2EMessage> messages) {
        logger.debug("sending messages count={}", messages.size());
        for (E2EMessage message : messages) {
            sendToRecipients(message);
        }
    }

    @Override
    public void close() {
        fanOut.close();
    }

    private void sendToRecipients(E2EMessage message) {
        List<String> recipients = message.recipients() == null ? List.of() : message.recipients();
        List<CompletableFuture<Void>> tasks = recipients.stream()
                .map(userId -> CompletableFuture.runAsync(() -> sendToUserDevices(userId, message), fanOut))
                .toList();
        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
    }

    private void sendToUserDevices(String userId, E2EMessage message) {
        for (String connectionId : wsConnections.getConnections(userId)) {
            var cLogger = logger.with("userId", userId).with("connectionId", connectionId);
            try {
                int status = wsgwClient.sendToConnection(connectionId, message);
                // The gateway no longer knows the connection: stop tracking it
                if (status == HttpURLConnection.HTTP_NOT_FOUND || status == HttpURLConnection.HTTP_GONE) {
                    wsConnections.removeConnection(userId, connectionId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cLogger.warn("interrupted while pushing message");
                return;
            } catch (IOException | RuntimeException e) {
                cLogger.error("failed to push message", e);
            }
        }
    }
}
