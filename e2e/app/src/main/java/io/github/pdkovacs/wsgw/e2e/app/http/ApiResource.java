package io.github.pdkovacs.wsgw.e2e.app.http;

import io.github.pdkovacs.wsgw.e2e.app.common.dto.E2EMessage;
import io.github.pdkovacs.wsgw.e2e.app.conntrack.WsConnections;
import io.github.pdkovacs.wsgw.e2e.app.push.WsgwClient;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Path("/api")
@ApplicationScoped
public class ApiResource {

    private static final Logger log = Logger.getLogger(ApiResource.class);
    private static final int MAX_CONCURRENT_RECIPIENT_SENDS = 4;

    private final WsConnections wsConnections;
    private final WsgwClient wsgwClient;
    private final ExecutorService fanOut = Executors.newFixedThreadPool(MAX_CONCURRENT_RECIPIENT_SENDS);

    public ApiResource(WsConnections wsConnections, WsgwClient wsgwClient) {
        this.wsConnections = wsConnections;
        this.wsgwClient = wsgwClient;
    }

    @PreDestroy
    void shutdown() {
        fanOut.shutdownNow();
    }

    @POST
    @Path("/message")
    public Response message(E2EMessage message) {
        sendToRecipients(message);
        return Response.noContent().build();
    }

    @POST
    @Path("/messages-in-bulk")
    public Response bulk(List<E2EMessage> messages) {
        log.debugf("sending messages count=%d", messages.size());
        for (E2EMessage message : messages) {
            sendToRecipients(message);
        }
        return Response.noContent().build();
    }

    private void sendToRecipients(E2EMessage message) {
        List<String> recipients = message.recipients() == null ? List.of() : message.recipients();
        List<CompletableFuture<Void>> tasks = recipients.stream()
                .map(userId -> CompletableFuture.runAsync(() -> sendToUserDevices(userId, message), fanOut))
                .toList();
        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
    }

    private void sendToUserDevices(String userId, E2EMessage message) {
        List<String> connIds = wsConnections.getConnections(userId);
        for (String connId : connIds) {
            try {
                int status = wsgwClient.sendToConnection(connId, message);
                if (status == Response.Status.NOT_FOUND.getStatusCode()) {
                    wsConnections.removeConnection(userId, connId);
                }
            } catch (Exception e) {
                log.errorf(e, "failed to push message user=%s connid=%s", userId, connId);
            }
        }
    }
}
