package io.github.pdkovacs.wsgw.e2e.driver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Learning test: pins down how the JDK's {@code java.net.http} {@link HttpClient} and
 * {@link WebSocket} behave, as far as the driver relies on it. It runs against the hand-rolled
 * {@link FakeWsServer}, so it can count TCP accepts and see the raw handshake request. If a JDK
 * upgrade turns one of these red, revisit the driver's assumptions.
 *
 * <p>The one that shapes the driver's listener most: configuring an executor does not keep
 * callbacks off the common pool, so a listener must not block, whatever the executor. Blocked common
 * pool workers stall {@link WebSocket} openings across the whole JVM.
 *
 * <p>{@link HttpClient#close()} blocks for as long as any of its WebSockets is open, so a test that
 * fails with one open would hang; the timeout's separate thread guards against that.
 */
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class JdkWebSocketClientLearningTest {

    private static final Logger log = LoggerFactory.getLogger(JdkWebSocketClientLearningTest.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final String COMMON_POOL_THREAD_PREFIX = "ForkJoinPool.commonPool-";

    /** Set while a task handed to a {@link #tagging} executor runs. */
    private static final ThreadLocal<Boolean> ON_EXECUTOR = ThreadLocal.withInitial(() -> false);

    private FakeWsServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new FakeWsServer();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.close();
    }

    @Test
    void defaultExecutorRunsListenerCallbacksOnPlatformThreads() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var recorder = exchange(client, 50, new Recorder(true, Duration.ZERO));

            assertThat(recorder.callbacks).isNotEmpty().noneMatch(Callback::virtual);
        }
    }

    @Test
    void onOpenRunsOnTheCommonPoolWithTheDefaultExecutor() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var recorder = exchange(client, 0, new Recorder(true, Duration.ZERO));

            assertOpenedOnTheCommonPool(recorder);
        }
    }

    @Test
    void onOpenRunsOnTheCommonPoolEvenWithAVirtualThreadExecutor() throws Exception {
        try (var vts = Executors.newVirtualThreadPerTaskExecutor();
             var client = HttpClient.newBuilder().executor(tagging(vts)).build()) {
            var recorder = exchange(client, 0, new Recorder(true, Duration.ZERO));

            assertOpenedOnTheCommonPool(recorder);
        }
    }

    /**
     * Frames that are already there when onOpen returns can still be delivered on onOpen's common
     * pool thread. Whether they are is a race, so only frames arriving later are pinned down here.
     */
    @Test
    void framesArrivingAfterOnOpenRunOnTheVirtualThreadExecutor() throws Exception {
        server.holdFrames();
        try (var vts = Executors.newVirtualThreadPerTaskExecutor();
             var client = HttpClient.newBuilder().executor(tagging(vts)).build()) {
            var recorder = new Recorder(true, Duration.ZERO);
            var ws = client.newWebSocketBuilder().buildAsync(server.wsUri(50), recorder)
                    .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            awaitUntil("onOpen", () -> !recorder.callbacks.isEmpty());
            Thread.sleep(300); // onOpen's thread finds no frame to deliver, and moves on

            server.releaseFrames();
            awaitUntil("50 messages", () -> recorder.texts.size() == 50);
            close(ws, recorder);

            assertThat(recorder.callbacks).filteredOn(callback -> callback.method().equals("onText"))
                    .hasSize(50)
                    .allMatch(Callback::virtual)
                    .allMatch(Callback::onExecutor);
        }
    }

    @Test
    void blockedCommonPoolKeepsAWebSocketFromOpeningEvenWithAVirtualThreadExecutor() throws Exception {
        var commonPool = ForkJoinPool.commonPool();
        var blocked = new CountDownLatch(commonPool.getParallelism());
        var release = new CountDownLatch(1);
        try (var vts = Executors.newVirtualThreadPerTaskExecutor();
             var client = HttpClient.newBuilder().executor(tagging(vts)).build()) {
            for (int i = 0; i < commonPool.getParallelism(); i++) {
                commonPool.execute(() -> {
                    blocked.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            var recorder = new Recorder(true, Duration.ZERO);
            CompletableFuture<WebSocket> opening = null;
            try {
                assertThat(blocked.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).as("sanity: pool blocked").isTrue();

                opening = client.newWebSocketBuilder().buildAsync(server.wsUri(0), recorder);
                awaitUntil("the handshake to reach the server", () -> !server.requests.isEmpty());
                Thread.sleep(300);
                assertThat(opening).isNotDone();
                assertThat(recorder.callbacks).isEmpty();

                release.countDown();
                close(opening.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), recorder);
            } finally {
                release.countDown();
                if (opening != null) {
                    opening.thenAccept(WebSocket::abort); // no-op once closed; else client.close() would block
                }
            }
        }
    }

    @Test
    void callbacksOfOneWebSocketNeverOverlapOnDefaultExecutor() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            // Demand is renewed first thing, so the next message is due while this one dwells
            var recorder = exchange(client, 100, new Recorder(true, Duration.ofMillis(2)));

            assertThat(recorder.maxInFlight).hasValue(1);
        }
    }

    @Test
    void callbacksOfOneWebSocketNeverOverlapOnVirtualThreadExecutor() throws Exception {
        try (var vts = Executors.newVirtualThreadPerTaskExecutor();
             var client = HttpClient.newBuilder().executor(tagging(vts)).build()) {
            var recorder = exchange(client, 100, new Recorder(true, Duration.ofMillis(2)));

            assertThat(recorder.maxInFlight).hasValue(1);
        }
    }

    @Test
    void noMessageIsDeliveredWithoutDemand() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var recorder = new Recorder(false, Duration.ZERO);
            var ws = client.newWebSocketBuilder().buildAsync(server.wsUri(5), recorder)
                    .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            // onOpen's request(1) is the only demand so far; the server has sent all five already
            awaitUntil("first message", () -> recorder.texts.size() == 1);
            Thread.sleep(300);
            assertThat(recorder.texts).containsExactly("m1");

            ws.request(4);
            awaitUntil("remaining messages", () -> recorder.texts.size() == 5);
            assertThat(recorder.texts).containsExactly("m1", "m2", "m3", "m4", "m5");

            ws.request(1); // in case onClose needs demand, too
            close(ws, recorder);
        }
    }

    @Test
    void selectorThreadIsAPlatformThreadWhateverTheExecutor() throws Exception {
        var before = selectorThreads();
        try (var vts = Executors.newVirtualThreadPerTaskExecutor();
             var client = HttpClient.newBuilder().executor(vts).build()) {
            // getAllStackTraces() lists platform threads only, so a virtual selector would not show up at all
            awaitUntil("selector thread", () -> newSince(before).size() == 1);

            assertThat(newSince(before)).singleElement()
                    .satisfies(t -> assertThat(t.isVirtual()).isFalse());
        }
    }

    @Test
    void eachClientHasItsOwnSelectorThreadWhichCloseStops() throws Exception {
        var before = selectorThreads();
        var clients = IntStream.range(0, 3).mapToObj(i -> HttpClient.newHttpClient()).toList();
        try {
            awaitUntil("three selector threads", () -> newSince(before).size() == 3);
        } finally {
            clients.forEach(HttpClient::close);
        }

        awaitUntil("selector threads to stop", () -> newSince(before).isEmpty());
    }

    @Test
    void everyWebSocketHoldsATcpConnectionOfItsOwnTakingAnIdleOneFromThePoolFirst() throws Exception {
        try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            var get = HttpRequest.newBuilder(server.httpUri()).build();
            client.send(get, HttpResponse.BodyHandlers.discarding());
            client.send(get, HttpResponse.BodyHandlers.discarding());
            assertThat(server.accepts).as("sanity: the pool reuses one TCP connection for both GETs").hasValue(1);

            var recorders = new ArrayList<Recorder>();
            var webSockets = new ArrayList<WebSocket>();
            try {
                for (int i = 0; i < 3; i++) {
                    var recorder = new Recorder(true, Duration.ZERO);
                    recorders.add(recorder);
                    webSockets.add(client.newWebSocketBuilder().buildAsync(server.wsUri(0), recorder)
                            .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
                }

                assertThat(server.requests).filteredOn(request -> request.requestLine().startsWith("GET /ws"))
                        .extracting(FakeWsServer.Request::clientPort)
                        .as("no two WebSockets share a TCP connection")
                        .hasSize(3)
                        .doesNotHaveDuplicates();
                assertThat(server.accepts).as("the first WebSocket took the GETs' idle TCP connection").hasValue(3);

                client.send(get, HttpResponse.BodyHandlers.discarding());
                assertThat(server.accepts).as("which left the pool: the next GET needs a new one").hasValue(4);

                for (int i = 0; i < 3; i++) {
                    close(webSockets.get(i), recorders.get(i));
                }
            } finally {
                webSockets.forEach(WebSocket::abort); // no-op once closed; else client.close() would block
            }
        }
    }

    @Test
    void handshakeIsHttp11EvenWhenClientPrefersHttp2() throws Exception {
        try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build()) {
            client.send(HttpRequest.newBuilder(server.httpUri()).build(), HttpResponse.BodyHandlers.discarding());
            assertThat(server.requests.getFirst().headers())
                    .as("contrast: a plain request from this client does try h2c")
                    .containsEntry("upgrade", "h2c");

            var recorder = new Recorder(true, Duration.ZERO);
            var ws = client.newWebSocketBuilder().buildAsync(server.wsUri(0), recorder)
                    .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            close(ws, recorder);

            var handshake = server.requests.getLast();
            assertThat(handshake.requestLine()).endsWith(" HTTP/1.1");
            assertThat(handshake.headers())
                    .containsEntry("upgrade", "websocket")
                    .doesNotContainKey("http2-settings");
        }
    }

    // -----------------------------------------------------------------------------
    // --- helpers ---
    // -----------------------------------------------------------------------------

    /** Opens a WebSocket that receives {@code messages} texts, waits for them, closes it. */
    private Recorder exchange(HttpClient client, int messages, Recorder recorder) throws Exception {
        var ws = client.newWebSocketBuilder().buildAsync(server.wsUri(messages), recorder)
                .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        awaitUntil(messages + " messages", () -> recorder.texts.size() == messages);
        close(ws, recorder);
        log.info("callback threads: {}", recorder.callbacks.stream()
                .collect(Collectors.groupingBy(Callback::summary, Collectors.counting())));
        return recorder;
    }

    private static void close(WebSocket ws, Recorder recorder) throws Exception {
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "").get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        recorder.closed.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static void assertOpenedOnTheCommonPool(Recorder recorder) {
        assertThat(recorder.callbacks.getFirst()).satisfies(callback -> {
            assertThat(callback.method()).isEqualTo("onOpen");
            assertThat(callback.threadName()).startsWith(COMMON_POOL_THREAD_PREFIX);
            assertThat(callback.virtual()).isFalse();
            assertThat(callback.onExecutor()).isFalse();
        });
    }

    private static Executor tagging(ExecutorService delegate) {
        return task -> delegate.execute(() -> {
            ON_EXECUTOR.set(true);
            try {
                task.run();
            } finally {
                ON_EXECUTOR.remove();
            }
        });
    }

    private static Set<Thread> selectorThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().endsWith("-SelectorManager"))
                .collect(Collectors.toSet());
    }

    private static Set<Thread> newSince(Set<Thread> before) {
        var now = new HashSet<>(selectorThreads());
        now.removeAll(before);
        return now;
    }

    private static void awaitUntil(String what, BooleanSupplier condition) throws InterruptedException {
        long giveUpAt = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - giveUpAt > 0) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(10);
        }
    }

    record Callback(String method, String threadName, boolean virtual, boolean onExecutor) {
        String summary() {
            var kind = onExecutor ? "executor"
                    : threadName.startsWith(COMMON_POOL_THREAD_PREFIX) ? "common-pool"
                    : threadName.endsWith("-SelectorManager") ? "selector"
                    : threadName.matches("HttpClient-\\d+-Worker-\\d+") ? "default-pool"
                    : "other";
            return "%s on %s %s".formatted(method, virtual ? "virtual" : "platform", kind);
        }
    }

    /** Records every callback's thread and how many callbacks of this listener overlapped. */
    private static final class Recorder implements WebSocket.Listener {
        final List<String> texts = new CopyOnWriteArrayList<>();
        final List<Callback> callbacks = new CopyOnWriteArrayList<>();
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        final AtomicInteger maxInFlight = new AtomicInteger();
        private final AtomicInteger inFlight = new AtomicInteger();
        private final boolean requestPerMessage;
        private final Duration dwell;

        Recorder(boolean requestPerMessage, Duration dwell) {
            this.requestPerMessage = requestPerMessage;
            this.dwell = dwell;
        }

        @Override
        public void onOpen(WebSocket ws) {
            enter("onOpen");
            try {
                ws.request(1);
            } finally {
                inFlight.decrementAndGet();
            }
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            enter("onText");
            try {
                texts.add(data.toString());
                if (requestPerMessage) {
                    ws.request(1);
                }
                if (!dwell.isZero()) {
                    Thread.sleep(dwell);
                }
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } finally {
                inFlight.decrementAndGet();
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            enter("onClose");
            try {
                closed.complete(null);
                return null;
            } finally {
                inFlight.decrementAndGet();
            }
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            closed.completeExceptionally(error);
        }

        private void enter(String method) {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            var t = Thread.currentThread();
            callbacks.add(new Callback(method, t.getName(), t.isVirtual(), ON_EXECUTOR.get()));
        }
    }
}
