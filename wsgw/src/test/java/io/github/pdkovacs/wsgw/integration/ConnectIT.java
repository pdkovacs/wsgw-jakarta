package io.github.pdkovacs.wsgw.integration;

import io.github.pdkovacs.wsgw.logging.CtxLogger;
import io.github.pdkovacs.wsgw.CircuitBreaker;
import io.github.pdkovacs.wsgw.Configuration;
import io.github.pdkovacs.wsgw.routehandlers.ConnectionRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(5)
public class ConnectIT {

    private static final CtxLogger logger = CtxLogger.of(ConnectIT.class);

    record AsyncConnecting(CountDownLatch connected, AtomicReference<Exception> exception) {
        void await() throws InterruptedException {
            connected.await();
            if (exception.get() != null) {
                throw new RuntimeException(exception.get());
            }
        }
    }

    final WsgwTestContext wsgwTestContext = new WsgwTestContext();

    @AfterEach
    public void tearDown() throws Exception {
        wsgwTestContext.tearDown();
    }

    @Test
    void connectSucceedsWithValidAPIKey(@TempDir Path tempDir) throws Exception {
        wsgwTestContext.setUp(tempDir);

        String wsgwServerName = wsgwTestContext.getWsgwServerName();
        String connId1 = this.wsgwTestContext.connectionIdGeneratorMock.roll();
        var wsTestClient1 = wsgwTestContext.wsTestClients.connect(wsgwServerName, wsgwTestContext.fakeAppConfig.getApiKey());
        assertThat(wsTestClient1.connectionId()).isEqualTo(connId1);
        String connId2 = this.wsgwTestContext.connectionIdGeneratorMock.roll();
        var wsTestClient2 = wsgwTestContext.wsTestClients.connect(wsgwServerName, wsgwTestContext.fakeAppConfig.getApiKey());
        assertThat(wsTestClient2.connectionId()).isEqualTo(connId2);
        assertFailureBeforeUpgrade("404 from unmapped rest endpoint", HttpServletResponse.SC_NOT_FOUND,
                "/some-unrelated-rest-endpoint", wsgwTestContext.fakeAppConfig.getApiKey());
    }

    private void assertFailureBeforeUpgrade(String assertionContext, int expectedHttpStatusCode, String wsgwPath,
                                            String[] apiKey) {
        HttpResponse<String> response = rawConnect(apiKey);
        assertThat(response.statusCode()).as(assertionContext).isEqualTo(expectedHttpStatusCode);
    }

    private HttpResponse<String> rawConnect(String[] apiKey) {
        String wsgwPath = "/connect";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://%s"
                        .formatted(wsgwTestContext.getWsgwServerName())
                        .concat(wsgwPath)))
                .header(apiKey[0], apiKey[1])
                .build();
        HttpResponse<String> response;
        try {
            response = wsgwTestContext.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            logger.error("Failed to connect to app ({})", wsgwPath, e);
            throw new RuntimeException(e);
        }
        return response;
    }

    private HttpResponse<String> rawConnect() {
        return rawConnect(wsgwTestContext.fakeAppConfig.getApiKey());
    }

    @Test
    void connectFailsWithInvalidAPIKey(@TempDir Path tempDir) throws Exception {
        wsgwTestContext.setUp(tempDir);

        var invalidAPIKey = new String[]{wsgwTestContext.fakeAppConfig.getApiKey()[0], wsgwTestContext.fakeAppConfig.getApiKey()[1].concat("kalap")};
        assertFailureBeforeUpgrade("plain GET to /connect with valid key", HttpServletResponse.SC_NOT_FOUND,
                "/connect", wsgwTestContext.fakeAppConfig.getApiKey());
        assertFailureBeforeUpgrade("401 from /connect handshake with invalid key", HttpServletResponse.SC_UNAUTHORIZED,
                "/connect", invalidAPIKey);
    }

    @Test
    void timeoutsOnAppWith504(@TempDir Path tempDir) throws Exception {
        var config = new Configuration();
        config.setBaseDir(tempDir.resolve("wsgw"));

        var timeOut = Duration.ofSeconds(3);

        config.setConnectWaitTimeout(timeOut.minus(Duration.ofSeconds(1)));
        wsgwTestContext.setUp(tempDir, config);

        var appConnectImplBlocking = new CountDownLatch(2);
        var unblockAppConnect = new CountDownLatch(1);
        try {
            assertThat(wsgwTestContext.meters.connectTimeouts()).isEqualTo(0);
            wsgwTestContext.fakeAppConfig.setConnectProcessingImpl(createWaitImpl(appConnectImplBlocking, unblockAppConnect));
            assertFailureBeforeUpgrade("504 from /connect handshake time-outing", HttpServletResponse.SC_GATEWAY_TIMEOUT,
                    "/connect", wsgwTestContext.fakeAppConfig.getApiKey());
            assertThat(wsgwTestContext.meters.connectTimeouts()).isEqualTo(1);
        } finally {
            unblockAppConnect.countDown();
        }
    }

    @Test
    void inflightConnectsGauge(@TempDir Path tempDir) throws Exception {
        // ARRANGE
        ArrayList<AsyncConnecting> asyncConnectings = new ArrayList<>();
        var appConnectImplBlocking = new CountDownLatch(2);
        var unblockAppConnect = new CountDownLatch(1);
        try {
            wsgwTestContext.setUp(tempDir);
            wsgwTestContext.fakeAppConfig.setConnectProcessingImpl(createWaitImpl(appConnectImplBlocking, unblockAppConnect));

            // ACT
            for (var i = 0; i < 2; i++) {
                asyncConnectings.add(connectAsync());
            }
            appConnectImplBlocking.await();

            // ASSERT
            assertThat(wsgwTestContext.meters.inflightConnects()).isEqualTo(2);
        } finally {
            unblockAppConnect.countDown();
            for (AsyncConnecting asyncConnecting : asyncConnectings) {
                asyncConnecting.await();
            }
        }
    }

    @Test
    void connectToAppLatencyIsMetered(@TempDir Path tempDir) throws Exception {
        var tcLogger = logger.with("test-case", "connectToAppLatencyIsMetered");

        wsgwTestContext.setUp(tempDir);

        var connectionEstablished = new CountDownLatch(1);
        AsyncConnecting asyncConnecting = null;
        try {
            wsgwTestContext.connectClient(connectionEstablished);

            assertThat(wsgwTestContext.meters.connectLatency().max(TimeUnit.SECONDS)).isLessThan(1);
            assertThat(wsgwTestContext.meters.connectLatency().max(TimeUnit.SECONDS)).isGreaterThan(0);

            wsgwTestContext.fakeAppConfig.setConnectProcessingImpl(() -> {
                try {
                    Thread.sleep(Duration.ofSeconds(2));
                } catch (InterruptedException e) {
                    tcLogger.warn("Connect app impl interrupted");
                }
            });
            asyncConnecting = connectAsync();
        } finally {
            if (asyncConnecting != null) {
                asyncConnecting.await();
            }
            connectionEstablished.await();
        }
        assertThat(wsgwTestContext.meters.connectLatency().max(TimeUnit.SECONDS)).isGreaterThan(2);
    }

    @Test
    @Timeout(6)
    void excessInflightConnectThrows503(@TempDir Path tempDir) throws Exception {
        var mLogger = logger.with("method", "excessInflightConnectThrows503");
        int maxInFlightConnects = 1;
        int latencySeconds = 2;
        var config = new Configuration();
        config.setMaxInFlightConnects(maxInFlightConnects);

        AsyncConnecting asyncConnecting = null;
        var unblockAppConnectImpl = new CountDownLatch(1);
        try {
            wsgwTestContext.setUp(tempDir, config);

            // Generate some latency metrics
            wsgwTestContext.fakeAppConfig.setConnectProcessingImpl(() -> {
                try {
                    Thread.sleep(Duration.ofSeconds(latencySeconds));
                } catch (InterruptedException e) {
                    mLogger.warn("Connect app impl interrupted");
                }
            });
            var latencyGeneratingConnectionEstablished = new CountDownLatch(1);
            wsgwTestContext.connectClient(latencyGeneratingConnectionEstablished);
            latencyGeneratingConnectionEstablished.await();

            var appConnectImplBlocking = new CountDownLatch(1);
            wsgwTestContext.fakeAppConfig.setConnectProcessingImpl(createWaitImpl(appConnectImplBlocking, unblockAppConnectImpl));

            asyncConnecting = connectAsync();
            appConnectImplBlocking.await();

            HttpResponse<String> response = rawConnect();
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.headers().firstValue("Retry-After").isPresent()).isTrue();
            assertRetryAfterWithinJitteredRange(response, latencySeconds, ConnectionRequest.MIN_ADMISSION_HOLD_DOWN_JITTER_FRACTION);

            assertThat(wsgwTestContext.meters.inflightConnects()).isEqualTo(1);
        } finally {
            unblockAppConnectImpl.countDown();
            if (asyncConnecting != null) {
                asyncConnecting.await();
            }
        }
    }

    @Test
    void connectionEstablishmentPreemptThresholdExceeded(@TempDir Path tempDir) throws Exception {
        var mLogger = logger.with("method", "connectionEstablishmentPreemptThresholdExceeded");

        var connectWaitTimeout = Duration.ofMillis(100);
        var connectFailurePreemptThreshold = 1;

        var config = new Configuration();
        config.setBaseDir(tempDir.resolve("wsgw"));
        config.setConnectFailurePreemptThreshold(connectFailurePreemptThreshold);


        config.setConnectWaitTimeout(connectWaitTimeout);
        wsgwTestContext.setUp(tempDir, config);
        wsgwTestContext.fakeAppConfig.setConnectProcessingImpl(() -> {
            try {
                Thread.sleep(connectWaitTimeout.plus(connectWaitTimeout));
            } catch (InterruptedException e) {
                mLogger.warn("Connect app impl interrupted");
            }
        });

        rawConnect();
        // And pass the threshold:
        rawConnect();

        // Trigger the signal:
        var response = rawConnect();
        assertThat(response.statusCode()).as("503 from /connect pre-empt").isEqualTo(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        // Retry-After is jittered to a random fraction in [minJitterFraction, 1.0]
        // of the true remaining hold-down (CircuitBreaker.jitteredRemaining()), so assert a range
        // rather than the exact remaining, with the same -1s slack for the seconds truncation as before.
        assertRetryAfterWithinJitteredRange(response, config.getConnectPreemptHoldDown().toSeconds() - 1, CircuitBreaker.MIN_JITTER_FRACTION);

        var moreHoldDownSec = 3;
        Thread.sleep(Duration.ofSeconds(moreHoldDownSec));
        response = rawConnect();
        assertRetryAfterWithinJitteredRange(response, config.getConnectPreemptHoldDown().toSeconds() - 1 - moreHoldDownSec, CircuitBreaker.MIN_JITTER_FRACTION);
    }

    private void assertRetryAfterWithinJitteredRange(HttpResponse<String> response, long exactRemainingSecs, double minJitterFraction) {
        var retryAfter = response.headers().firstValue("Retry-After");
        assertThat(retryAfter).as("503 from /connect pre-empt hold-down period").isPresent();
        var retryAfterSecs = Long.parseLong(retryAfter.get());
        var lowerBoundSecs = Math.round(exactRemainingSecs * minJitterFraction) - 1;
        assertThat(retryAfterSecs)
                .as("503 from /connect pre-empt hold-down period, jittered")
                .isBetween(lowerBoundSecs, exactRemainingSecs);
    }

    private AsyncConnecting connectAsync() throws Exception {
        CountDownLatch connectionEstablished = new CountDownLatch(1);
        final AtomicReference<Exception> exception = new AtomicReference<Exception>();
        String wsgwServerName = wsgwTestContext.getWsgwServerName();
        Thread.ofVirtual().start(() -> {
            this.wsgwTestContext.connectionIdGeneratorMock.roll();
            try {
                wsgwTestContext.wsTestClients.connect(
                        wsgwServerName,
                        wsgwTestContext.fakeAppConfig.getApiKey(),
                        connectionEstablished
                );
            } catch (Exception e) {
                exception.set(e);
                logger.error("[connectAsync]: test client failed to connect", e);
                connectionEstablished.countDown();   // let await() through to the check
            }
        });

        return new AsyncConnecting(connectionEstablished, exception);
    }

    static Runnable createWaitImpl(CountDownLatch readyForBlocking, CountDownLatch unblock) {
        return () -> {
            var mLogger = logger.with("method", "createWaitImpl");
            try {
                readyForBlocking.countDown();
                mLogger.debug("about to get busy...");
                unblock.await();
                mLogger.debug("no longer busy");
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        };
    }
}
