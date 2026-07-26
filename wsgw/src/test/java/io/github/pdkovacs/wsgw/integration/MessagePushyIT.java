package io.github.pdkovacs.wsgw.integration;

import io.github.pdkovacs.wsgw.logging.CtxLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stress ("pushy") sibling of {@link MessageIT}, kept in its own class because it plays by
 * different rules than the functional tests:
 *
 * Rate, not fail-fast. At this volume a stray transport reset is physical, not a
 * gateway defect. Rather than aborting on the first one, every task runs to completion,
 * failures are collected, and we assert on the aggregate rate. Delivery correctness is still
 * every push that got an OK response must reach its inbox.
 */
public class MessagePushyIT {

    private static final CtxLogger logger = CtxLogger.of(MessagePushyIT.class);

    // Fraction of transport-level send attempts allowed to fail (e.g. a reset on the h2c upgrade)
    // before the run is considered broken. Set to 0.0 to demand a perfectly clean run.
    private static final double MAX_TRANSPORT_FAILURE_RATE = 0.00; // 0.01 is OKish

    // How long a client may go without receiving an expected message before we call it lost.
    private static final int DELIVERY_QUIESCENCE_SECONDS = 15;

    // Cap on concurrently in-flight app->client pushes. Keeps the offered load high without letting
    // ~1,000,000 virtual threads (and their buffers) pile up and thrash the *test* JVM's heap.
    private static final int MAX_IN_FLIGHT_PUSHES = 20000;

    private final WsgwTestContext wsgwTestContext = new WsgwTestContext();

    private record ClientTestCtx(
            WebsocketTestClient testClient,
            Collection<String> ackedToApp,      // client->app sends that returned OK
            Collection<String> ackedToClient    // app->client pushes that returned OK
    ) {}

    @BeforeEach
    public void setUp(@TempDir Path tempDir) throws Exception {
        wsgwTestContext.setUp(tempDir);
    }

    @AfterEach
    public void tearDown() throws Exception {
        wsgwTestContext.tearDown();
    }

    @Test
    @Timeout(300)
    void sendReceiveMessagesFromAppMultipleClientsPushy() throws Exception {
        final var tcLogger = logger.with("method", "sendReceiveMessagesFromAppMultipleClientsPushy");

        final int nrClients = 1000;
        final int nrMessagesToSend = 1000;

        final String wsgwServerName = wsgwTestContext.getWsgwServerName();
        final WsTestClients testClients = wsgwTestContext.wsTestClients;

        final Collection<ClientTestCtx> processed = new ConcurrentLinkedQueue<>();
        final Collection<Throwable> transportFailures = new ConcurrentLinkedQueue<>();
        final LongAdder attempts = new LongAdder();

        // Phase 1: establish every client connection. Each gets its own HTTP push client on
        // purpose: 1000 separate h2 connections spread the push load across 1000 Tomcat
        // connection processors. A single shared connection would serialize every push through
        // one processor (idle cores) and turn one reset into a mass-failure of all its streams.
        try (var connectExec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < nrClients; i++) {
                connectExec.submit(() -> {
                    try {
                        var client = testClients.connect(wsgwServerName, wsgwTestContext.apiKey);
                        processed.add(new ClientTestCtx(client,
                                new ConcurrentLinkedQueue<>(), new ConcurrentLinkedQueue<>()));
                    } catch (Throwable t) {
                        transportFailures.add(t);
                    }
                    return null;
                });
            }
        } // awaits all connects

        // Phase 2: drive the message load, but cap concurrent in-flight pushes. Without a cap,
        // 1000 clients x 1000 pushes put ~1,000,000 virtual threads (plus buffers) in flight at
        // once and thrash the test JVM into GC oblivion -- exactly what the original fail-fast
        // quietly prevented by aborting at the first error. The semaphore keeps offered load
        // high but finite, so we measure the gateway rather than an OOM.
        final Semaphore inFlight = new Semaphore(MAX_IN_FLIGHT_PUSHES);
        try (var sendExec = Executors.newVirtualThreadPerTaskExecutor()) {
            // client -> app: one sequential stream per client (a single WS session must not be
            // sent to concurrently).
            for (ClientTestCtx ctx : processed) {
                sendExec.submit(() -> {
                    var client = ctx.testClient();
                    var connId = client.connectionId();
                    try {
                        for (int i = 0; i < nrMessagesToSend; i++) {
                            attempts.increment();
                            ctx.ackedToApp().add(sendMessageFromClientToApp(client, connId));
                        }
                    } catch (Throwable t) {
                        transportFailures.add(t); // a broken session abandons this client's stream
                    }
                    return null;
                });
            }
            // app -> client: pushes fired concurrently across all clients, throttled so at most
            // MAX_IN_FLIGHT_PUSHES are outstanding (and alive) at any instant.
            for (ClientTestCtx ctx : processed) {
                var client = ctx.testClient();
                for (int i = 0; i < nrMessagesToSend; i++) {
                    inFlight.acquire();
                    attempts.increment();
                    sendExec.submit(() -> {
                        try {
                            ctx.ackedToClient().add(client.postMessageFromApp());
                        } catch (Throwable t) {
                            transportFailures.add(t);
                        } finally {
                            inFlight.release();
                        }
                        return null;
                    });
                }
            }
        } // awaits all sends

        // Correctness: every acked message must have been delivered to the far inbox.
        assertAllAckedDelivered(processed);

        // Health: the transport-error rate must stay under the tolerance.
        assertFailureRateWithinTolerance(attempts, transportFailures, tcLogger);
    }

    private void assertAllAckedDelivered(Collection<ClientTestCtx> processed) throws Exception {
        final Collection<String> gaps = new ConcurrentLinkedQueue<>();
        try (var verifyExec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (ClientTestCtx ctx : processed) {
                verifyExec.submit(() -> {
                    var connId = ctx.testClient().connectionId();
                    awaitDelivered("client " + connId, ctx.testClient().messageInbox(), ctx.ackedToClient(), gaps);
                    awaitDelivered("app " + connId, wsgwTestContext.getAppInbox(connId), ctx.ackedToApp(), gaps);
                    return null;
                });
            }
        }
        assertThat(gaps).as("acked-but-undelivered messages").isEmpty();
    }

    // Drains an inbox until every acked message has been seen (extras from at-least-once retries are
    // ignored). Reports a gap if the stream goes quiet before all acked messages arrive.
    private static void awaitDelivered(String who, java.util.concurrent.BlockingQueue<Message> inbox,
                                       Collection<String> acked, Collection<String> gaps) throws InterruptedException {
        Set<String> remaining = new HashSet<>(acked);
        while (!remaining.isEmpty()) {
            Message m = inbox.poll(DELIVERY_QUIESCENCE_SECONDS, SECONDS);
            if (m == null) {
                gaps.add(who + ": " + remaining.size() + " acked message(s) never arrived");
                return;
            }
            if (m instanceof Message.Text(String text)) {
                remaining.remove(text);
            }
        }
    }

    private void assertFailureRateWithinTolerance(LongAdder attempts, Collection<Throwable> failures, CtxLogger log) {
        long total = attempts.sum();
        long failed = failures.size();
        double rate = total == 0 ? 0.0 : (double) failed / total;
        Map<String, Long> byType = failures.stream().collect(
                Collectors.groupingBy(t -> rootCause(t).getClass().getSimpleName(), Collectors.counting()));

        log.info("Pushy load done: attempts={}, transport failures={} ({}), breakdown={}",
                total, failed, "%.4f%%".formatted(rate * 100), byType);

        assertThat(rate)
                .as("transport failure rate %d/%d, breakdown %s", failed, total, byType)
                .isLessThanOrEqualTo(MAX_TRANSPORT_FAILURE_RATE);
    }

    private static String sendMessageFromClientToApp(WebsocketTestClient client, String connId) throws IOException {
        final String message = "%s from client over %s".formatted(Math.random(), connId);
        client.websocketClientSession().getBasicRemote().sendText(message);
        return message;
    }

    private static Throwable rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c;
    }
}
