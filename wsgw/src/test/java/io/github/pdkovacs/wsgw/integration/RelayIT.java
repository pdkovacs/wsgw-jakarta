package io.github.pdkovacs.wsgw.integration;

import io.github.pdkovacs.wsgw.Configuration;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The RELAY flow (client → app) on its inbound hop, client_to_gw: the per-connection relay buffer.
 * {@link Delivery} covers what happens to the relayed messages; {@link FillBands} covers how the buffer
 * shows up in wsgw.relay.buffer.connections{fill=empty|low|high|full}. Contract: docs/backpressure.md
 * §2.5.1.
 */
@Timeout(5)
public class RelayIT {

    private static final CtxLogger logger = CtxLogger.of(RelayIT.class);

    // Large enough for every fill band to be reachable: low = 1..2, high = 3, full = 4.
    private static final int BUFFER_BOUND = 4;

    final WsgwTestContext wsgwTestContext = new WsgwTestContext();

    private final AtomicInteger messageSequence = new AtomicInteger();

    @AfterEach
    public void tearDown() throws Exception {
        wsgwTestContext.tearDown();
    }

    @Nested
    @DisplayName("delivery")
    class Delivery {

        @BeforeEach
        public void setUp(@TempDir Path tempDir) throws Exception {
            var config = new Configuration();
            config.setAppwardDispatcherQueueSize(BUFFER_BOUND);
            wsgwTestContext.setUp(tempDir, config);
        }

        @Test
        @DisplayName("a stalled connection does not hold up another connection's relay")
        void stalledConnectionDoesNotHoldUpOthers() throws Exception {
            var stalledClient = connect();
            var flowingClient = connect();
            var stall = stall(stalledClient);
            try {
                sendToApp(stalledClient);
                stall.awaitInFlight();

                var message = sendToApp(flowingClient);
                assertThat(nextTextAtApp(flowingClient)).isEqualTo(message);
            } finally {
                stall.release();
            }
        }

        @Test
        @DisplayName("frames buffered while the app was stalled all reach it, in order, once it drains again")
        void framesBufferedDuringStallAreDeliveredInOrder() throws Exception {
            var client = connect();
            var stall = stall(client);
            List<String> sent;
            try {
                sent = fillToFull(client, stall);
                awaitBufferFull();
            } finally {
                stall.release();
            }

            for (var message : sent) {
                assertThat(nextTextAtApp(client)).isEqualTo(message);
            }
        }
    }

    @Nested
    @DisplayName("fill bands")
    class FillBands {

        @BeforeEach
        public void setUp(@TempDir Path tempDir) throws Exception {
            var config = new Configuration();
            config.setAppwardDispatcherQueueSize(BUFFER_BOUND);
            wsgwTestContext.setUp(tempDir, config);
        }

        @Test
        @DisplayName("idle connections are all in the empty band, and the bands sum to the connection count")
        void idleConnectionsAreEmpty() throws Exception {
            int connectionCount = 3;
            for (int i = 0; i < connectionCount; i++) {
                connect();
            }

            wsgwTestContext.assertBandsEventually(connectionCount, 0, 0, 0);
        }

        @Test
        @DisplayName("a connection whose app side is stalled moves through low, high and full as frames arrive")
        void stalledConnectionFillsThroughTheBands() throws Exception {
            var client = connect();
            var stall = stall(client);
            try {
                sendToApp(client);
                stall.awaitInFlight();
                // The drain took that frame off the buffer before blocking on the app, so it is not counted.
                wsgwTestContext.assertBandsEventually(1, 0, 0, 0);

                sendToApp(client);
                wsgwTestContext.assertBandsEventually(0, 1, 0, 0);

                // Depth 2 is BUFFER_BOUND / 2, still low: not observable as a band change, so no assertion here.
                sendToApp(client);
                sendToApp(client);
                wsgwTestContext.assertBandsEventually(0, 0, 1, 0);

                sendToApp(client);
                wsgwTestContext.assertBandsEventually(0, 0, 0, 1);
            } finally {
                stall.release();
            }
        }

        @Test
        @DisplayName("a stalled connection is counted as full, an idle one beside it as empty")
        void stalledAndIdleConnectionsLandInSeparateBands() throws Exception {
            var stalledClient = connect();
            connect();
            var stall = stall(stalledClient);
            try {
                fillToFull(stalledClient, stall);
                wsgwTestContext.assertBandsEventually(1, 0, 0, 1);
            } finally {
                stall.release();
            }
        }

        @Test
        @DisplayName("once the app drains again, a full connection returns to the empty band")
        void drainedConnectionReturnsToEmpty() throws Exception {
            var client = connect();
            var stall = stall(client);
            try {
                fillToFull(client, stall);
                wsgwTestContext.assertBandsEventually(0, 0, 0, 1);
            } finally {
                stall.release();
            }

            wsgwTestContext.assertBandsEventually(1, 0, 0, 0);
        }
    }

    @Nested
    @DisplayName("enqueue")
    class Enqueue {
        @BeforeEach
        public void setUp(@TempDir Path tempDir) throws Exception {
            var config = new Configuration();
            config.setAppwardDispatcherQueueSize(1);
            wsgwTestContext.setUp(tempDir, config);
        }

        @Test
        @DisplayName("drops frame on relayEnqueueTimeout expiry")
        void dropsFramesOnRelayEnqueueTimeout() throws Exception {
            var stalledClient = connect();
            var stall = stall(stalledClient);
            try {
                sendToApp(stalledClient);
                stall.awaitInFlight();
                sendToApp(stalledClient);
            } finally {
                stall.release();
            }
        }
    }

    // Holds the app's handling of relayed messages for one connection until released; messages for
    // any other connection pass straight through.
    private record Stall(String connectionId, CountDownLatch inFlight,
                         CountDownLatch released) implements Consumer<String> {
        @Override
        public void accept(String relayedForConnectionId) {
            if (!connectionId.equals(relayedForConnectionId)) {
                return;
            }
            inFlight.countDown();
            try {
                released.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        void awaitInFlight() throws InterruptedException {
            assertThat(inFlight.await(WsgwTestContext.SETTLE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                    .as("a relayed message reached the stalled app")
                    .isTrue();
        }

        void release() {
            released.countDown();
        }
    }

    private Stall stall(WebsocketTestClient client) {
        var stall = new Stall(client.connectionId(), new CountDownLatch(1), new CountDownLatch(1));
        wsgwTestContext.fakeAppConfig.setMessageProcessingImpl(stall);
        return stall;
    }

    // The first frame is taken off the buffer and held in flight by the stalled app; the next
    // BUFFER_BOUND frames fill the buffer.
    private List<String> fillToFull(WebsocketTestClient client, Stall stall) throws IOException, InterruptedException {
        var sent = new ArrayList<String>();
        sent.add(sendToApp(client));
        stall.awaitInFlight();
        for (int i = 0; i < BUFFER_BOUND; i++) {
            sent.add(sendToApp(client));
        }
        return sent;
    }

    // A precondition, not the assertion under test: the fill bands are the only outside view of whether
    // the frames have actually landed in the buffer. Assumes the stalled connection is the only one.
    private void awaitBufferFull() throws InterruptedException {
        wsgwTestContext.assertBandsEventually(0, 0, 0, 1);
    }

    private WebsocketTestClient connect() throws Exception {
        wsgwTestContext.connectionIdGeneratorMock.roll();
        return wsgwTestContext.wsTestClients.connect(wsgwTestContext.getWsgwServerName(), wsgwTestContext.fakeAppConfig.getApiKey());
    }

    private String sendToApp(WebsocketTestClient client) throws IOException {
        var message = "message %d over %s".formatted(messageSequence.incrementAndGet(), client.connectionId());
        client.sendText(message);
        logger.debug("sent to app: {}", message);
        return message;
    }

    private String nextTextAtApp(WebsocketTestClient client) throws InterruptedException {
        var received = wsgwTestContext.getAppInbox(client.connectionId()).poll(WsgwTestContext.SETTLE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        return switch (received) {
            case Message.Text(String text) -> text;
            case Message.EndOfStream _ -> fail("Expected a relayed message, got EndOfStream");
            case null -> fail("No relayed message reached the app within %s".formatted(WsgwTestContext.SETTLE_TIMEOUT));
        };
    }
}
