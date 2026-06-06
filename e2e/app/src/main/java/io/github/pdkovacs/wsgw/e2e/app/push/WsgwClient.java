package io.github.pdkovacs.wsgw.e2e.app.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pdkovacs.wsgw.e2e.app.common.dto.E2EMessage;
import io.github.pdkovacs.wsgw.e2e.app.common.wsgw.WsgwLocator;
import io.github.pdkovacs.wsgw.e2e.app.common.wsgw.WsgwPaths;
import io.github.pdkovacs.wsgw.e2e.app.config.AppConfig;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Singleton
public class WsgwClient {

    private static final Logger log = Logger.getLogger(WsgwClient.class);

    private final AppConfig appConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpClient httpClient;
    private String baseUrl;

    public WsgwClient(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    @PostConstruct
    void init() {
        WsgwLocator locator = appConfig.wsgwLocator();
        this.baseUrl = "http://" + locator.wsgwHost() + ":" + locator.wsgwPort();
        // Iter 2: flip to HTTP_2 when h2c parity is wired up.
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public int sendToConnection(String connectionId, E2EMessage message) throws Exception {
        String url = baseUrl + WsgwPaths.MESSAGE_PATH + "/" + connectionId;
        byte[] body = objectMapper.writeValueAsBytes(message);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        log.debugf("POST %s -> %d", url, response.statusCode());
        return response.statusCode();
    }
}
