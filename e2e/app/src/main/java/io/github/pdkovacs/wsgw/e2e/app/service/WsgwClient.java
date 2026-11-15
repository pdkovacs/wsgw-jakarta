package io.github.pdkovacs.wsgw.e2e.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pdkovacs.wsgw.WsgwPaths;
import io.github.pdkovacs.wsgw.e2e.common.dto.E2EMessage;
import io.github.pdkovacs.wsgw.logging.CtxLogger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The app's side of the PUSH flow's app-to-gateway hop: one POST per connection.
 */
public class WsgwClient {

    private static final CtxLogger logger = CtxLogger.of(WsgwClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final String baseUrl;

    /**
     * @param wsgwServer the gateway's {@code host:port}
     */
    public WsgwClient(HttpClient httpClient, String wsgwServer) {
        this.httpClient = httpClient;
        this.baseUrl = "http://" + wsgwServer;
    }

    /**
     * @return the gateway's HTTP status code
     */
    public int sendToConnection(String connectionId, E2EMessage message) throws IOException, InterruptedException {
        String url = baseUrl + WsgwPaths.MESSAGE_FROM_APP + "/" + connectionId;
        byte[] body = MAPPER.writeValueAsBytes(message);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .build();

        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        logger.with("connectionId", connectionId).debug("POST {} -> {}", url, response.statusCode());
        return response.statusCode();
    }
}
