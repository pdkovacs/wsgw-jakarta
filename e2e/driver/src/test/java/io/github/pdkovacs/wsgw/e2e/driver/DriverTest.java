package io.github.pdkovacs.wsgw.e2e.driver;

import io.github.pdkovacs.wsgw.e2e.common.auth.PasswordCredentials;
import io.github.pdkovacs.wsgw.version.VersionServlet;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DriverTest {

    private static final String AUTHORIZATION = "Basic " + Base64.getEncoder()
            .encodeToString("user1:secret".getBytes(StandardCharsets.UTF_8));

    @TempDir
    Path baseDir;

    private final HttpClient http = HttpClient.newHttpClient();

    private FakeWsServer gateway;
    private Driver driver;
    private int port;

    @BeforeEach
    void start() throws Exception {
        gateway = new FakeWsServer();

        var config = new Config();
        config.setServerPort(0);
        config.setBaseDir(baseDir);
        var hostAndPort = gateway.hostAndPort().split(":");
        config.setWsgwHost(hostAndPort[0]);
        config.setWsgwPort(Integer.parseInt(hostAndPort[1]));
        config.setPasswordCredentials(List.of(
                new PasswordCredentials("user1", "secret"),
                new PasswordCredentials("user2", "secret")));

        driver = new Driver(config, new SimpleMeterRegistry());
        port = driver.start();
    }

    @AfterEach
    void stop() throws Exception {
        driver.stop();
        gateway.close();
    }

    @Test
    void versionIsPublic() throws Exception {
        var response = http.send(request(VersionServlet.PATH).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void runNeedsCredentials() throws Exception {
        var response = http.send(request(RunnerServlet.PATH + "?user-count=2")
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(gateway.accepts).hasValue(0);
    }

    @Test
    void runConnectsItsUsersToTheGateway() throws Exception {
        var response = http.send(request(RunnerServlet.PATH + "?user-count=2")
                .header("Authorization", AUTHORIZATION)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).isNotBlank();
        awaitHandshakes(2);
    }

    // -----------------------------------------------------------------------------
    // --- helpers ---
    // -----------------------------------------------------------------------------

    private HttpRequest.Builder request(String pathAndQuery) {
        return HttpRequest.newBuilder(URI.create("http://localhost:%d%s".formatted(port, pathAndQuery)));
    }

    private void awaitHandshakes(int expected) throws InterruptedException {
        long giveUpAt = System.nanoTime() + 5_000_000_000L;
        while (gateway.requests.size() < expected && System.nanoTime() - giveUpAt < 0) {
            Thread.sleep(10);
        }
        assertThat(gateway.requests).hasSize(expected);
    }
}
