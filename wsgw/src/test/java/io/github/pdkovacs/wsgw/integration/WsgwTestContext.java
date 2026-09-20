package io.github.pdkovacs.wsgw.integration;

import io.github.pdkovacs.wsgw.Configuration;
import io.github.pdkovacs.wsgw.Wsgw;
import io.github.pdkovacs.wsgw.appward.Request;
import io.github.pdkovacs.wsgw.integration.app.fake.FakeApp;
import io.github.pdkovacs.wsgw.integration.app.fake.FakeAppConfig;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import io.github.pdkovacs.wsgw.socket.WsConnection.State;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

public class WsgwTestContext {

    public static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(2);
    private static final CtxLogger logger = CtxLogger.of(WsgwTestContext.class);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(10);
    public static final int APPWARD_DISPATCHER_QUEUE_SIZE = 1;
    public static final Duration DEFAULT_PUSH_REQUEST_TIMEOUT = Duration.ofSeconds(5);

    record Meters(MeterRegistry registry) {
        int inflightConnects() {
            return (int) registry.get("wsgw.connect.inflight").tag("flow", "connect").tag("site", "gw_to_app")
                    .gauge().value();
        }

        int connectTimeouts() {
            return (int) registry.get("wsgw.connect.timeouts").tag("flow", "connect").tag("site", "gw_to_app").counter().count();
        }

        Timer connectLatency() {
            return registry.get("wsgw.connect.latency").tag("flow", "connect").tag("site", "gw_to_app").timer();
        }

        int connections(State state) {
            return (int) registry.get("wsgw.connections").tag("state", state.tagValue()).gauge().value();
        }

        int relayBufferConnections(String fill) {
            return (int) registry.get("wsgw.relay.buffer.connections").tag("flow", "relay").tag("site", "client_to_gw")
                    .tag("fill", fill).gauge().value();
        }

        int relayEnqueueDrops() {
            return (int) registry.get("wsgw.relay.enqueue.drops")
                    .tag("flow", "relay")
                    .tag("site", "client_to_gw").counter().count();
        }
    }

    private final FakeApp fakeApp = new FakeApp();

    final ConnectionIdGeneratorMock connectionIdGeneratorMock = new ConnectionIdGeneratorMock();
    final HttpClient httpClient = Request.createHttpClient();
    WsTestClients wsTestClients;
    Meters meters;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private Wsgw wsgw;
    private String wsgwServerName;

    FakeAppConfig fakeAppConfig;

    public void setUp(Path tempDir, Configuration wsgwConfig, Duration pushRequestTimeout) throws Exception {
        Objects.requireNonNull(pushRequestTimeout);
        wsTestClients = new WsTestClients(pushRequestTimeout);
        fakeAppConfig = new FakeAppConfig(tempDir, new String[]{"XKEY", "asdfqwe"});
        int appPort = fakeApp.start(fakeAppConfig);
        String appBaseUrl = "http://localhost:%d".formatted(appPort);
        wsgwConfig.setAppBaseUrl(appBaseUrl);
        wsgwConfig.setBaseDir(tempDir.resolve("wsgw"));

        wsgw = new Wsgw(wsgwConfig, meterRegistry, connectionIdGeneratorMock);
        wsgwServerName = "localhost:%d".formatted(wsgw.start());
        meters = new Meters(meterRegistry);
    }

    public void setUp(Path tempDir, Configuration wsgwConfig) throws Exception {
        setUp(tempDir, wsgwConfig, DEFAULT_PUSH_REQUEST_TIMEOUT);
    }


    public void setUp(Path tempDir) throws Exception {
        var config = new Configuration();
        config.setBaseDir(tempDir.resolve("wsgw"));
        config.setAppwardDispatcherQueueSize(1);
        setUp(tempDir, config, Duration.ofSeconds(5));
    }

    public void tearDown() throws Exception {
        logger.debug("Tearing down WsgwTestContext");
        wsTestClients.close();
        wsgw.stop();
        fakeApp.stop();
        httpClient.close();
    }

    public String getWsgwServerName() {
        return wsgwServerName;
    }

    void connectClient(CountDownLatch connectionEstablished) throws Exception {
        wsTestClients.connect(
                wsgwServerName,
                fakeAppConfig.getApiKey(),
                connectionEstablished
        );
    }

    public BlockingQueue<Message> getAppInbox(String connectionId) {
        return fakeApp.getConnection(connectionId).getMessageInbox();
    }

    public List<BlockingQueue<Message>> getAppInboxes() {
        return this.fakeApp.getInboxes();
    }

    List<Integer> state() {
        return List.of(
                meters.connections(State.AWAITING_REGISTRATION),
                meters.connections(State.REGISTERED),
                meters.connections(State.AWAITING_TERMINATION));
    }

    void assertRegisteredConnectionCountEventually(int registered, String description) throws InterruptedException {
        int awaitingRegistration = 0;
        int awaitingTermination = 0;
        var expected = List.of(awaitingRegistration, registered, awaitingTermination);
        assertEventually(this::state, expected, SETTLE_TIMEOUT, description);
    }

    List<Integer> bands() {
        return List.of(
                meters.relayBufferConnections("empty"),
                meters.relayBufferConnections("low"),
                meters.relayBufferConnections("high"),
                meters.relayBufferConnections("full"));
    }

    // A frame lands in the gateway's buffer some time after sendText returns, so poll the bands until
    // they settle rather than asserting on a single reading.
    void assertBandsEventually(int empty, int low, int high, int full) throws InterruptedException {
        var description = "relay buffer connections by fill band [empty, low, high, full]";
        assertBandsEventually(empty, low, high, full, description);
    }

    void assertBandsEventually(int empty, int low, int high, int full, String description) throws InterruptedException {
        var expected = List.of(empty, low, high, full);
        assertEventually(this::bands, expected, SETTLE_TIMEOUT, description);
    }

    @SuppressWarnings("BusyWait")
    public static <T> void assertEventually(Supplier<T> actual, T expected, Duration timeout, String description)
            throws InterruptedException {
        var deadline = System.nanoTime() + timeout.toNanos();
        var last = actual.get();
        while (!Objects.equals(last, expected) && System.nanoTime() < deadline) {
            Thread.sleep(POLL_INTERVAL.toMillis());
            last = actual.get();
        }
        assertThat(last).as(description).isEqualTo(expected);
    }
}