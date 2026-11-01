package io.github.pdkovacs.wsgw.e2e.driver.web;

import io.github.pdkovacs.wsgw.e2e.common.auth.PasswordCredentials;
import io.github.pdkovacs.wsgw.e2e.driver.Monitoring;
import io.github.pdkovacs.wsgw.e2e.driver.service.TestRunner;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RunnerServletTest {

    private record Counts(int userCount, int messageCount) {}

    private static final List<PasswordCredentials> POOL = List.of(
            new PasswordCredentials("user1", "secret"),
            new PasswordCredentials("user2", "secret"),
            new PasswordCredentials("user3", "secret"));

    @TempDir
    Path baseDir;

    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicReference<Counts> requestedCounts = new AtomicReference<>();

    private Tomcat tomcat;

    @BeforeEach
    void startTomcat() throws Exception {
        var testRunner = new TestRunner(http, POOL, POOL.getFirst(), Monitoring.noop()) {
            @Override
            public String createStartTestRun(int userCount, int messageCount) {
                requestedCounts.set(new Counts(userCount, messageCount));
                return super.createStartTestRun(userCount, messageCount);
            }
        };

        tomcat = new Tomcat();
        tomcat.setBaseDir(baseDir.toString());
        tomcat.setPort(0);
        tomcat.getConnector();
        Context ctx = tomcat.addContext("", baseDir.toString());
        Tomcat.addServlet(ctx, "run", new RunnerServlet(http, testRunner)).addMapping("/run");
        tomcat.start();
    }

    @AfterEach
    void stopTomcat() throws Exception {
        tomcat.stop();
        tomcat.destroy();
    }

    @Test
    void messageCountDefaultsToUserCount() throws Exception {
        assertThat(get("user-count=2").statusCode()).isEqualTo(202);
        assertThat(requestedCounts).hasValue(new Counts(2, 2));
    }

    @Test
    void messageCountOverridesTheDefault() throws Exception {
        assertThat(get("user-count=2&message-count=7").statusCode()).isEqualTo(202);
        assertThat(requestedCounts).hasValue(new Counts(2, 7));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "user-count=many",
            "user-count=0",
            "user-count=4",
            "user-count=2&message-count=many",
            "user-count=2&message-count=",
            "user-count=2&message-count=0"
    })
    void rejectsInvalidCounts(String query) throws Exception {
        assertThat(get(query).statusCode()).isEqualTo(400);
    }

    // -----------------------------------------------------------------------------
    // --- helpers ---
    // -----------------------------------------------------------------------------

    private HttpResponse<String> get(String query) throws Exception {
        var uri = URI.create("http://localhost:%d/run?%s".formatted(tomcat.getConnector().getLocalPort(), query));
        return http.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
