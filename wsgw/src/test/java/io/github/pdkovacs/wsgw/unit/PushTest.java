package io.github.pdkovacs.wsgw.unit;

import io.github.pdkovacs.wsgw.CircuitBreaker;
import io.github.pdkovacs.wsgw.backpressure.RetryAfter;
import io.github.pdkovacs.wsgw.backpressure.SendLockWaitTimedOut;
import io.github.pdkovacs.wsgw.socket.WsConnections;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import io.github.pdkovacs.wsgw.unit.support.WsConnectionsFixture;
import io.github.pdkovacs.wsgw.unit.support.WsConnectionsFixture.ConnectionsUnderTest;
import jakarta.websocket.RemoteEndpoint;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.stubbing.Answer;

import static io.github.pdkovacs.wsgw.unit.support.WsConnectionsFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * The push flow's backpressure (flow=push, site=gw_to_client): the happy path, and what a push does
 * when the send path is already busy -- fail fast, then shed once the breaker trips.
 * The registration-race (flow=connect) side of WsConnections lives in {@link ConnectTest}.
 */
@Timeout(5)
public class PushTest {

    private static final CtxLogger logger = CtxLogger.of(WsConnections.class);

    @Test
    @DisplayName("the happy path")
    void lonelyPushSendsMessageOverRegisteredSession() throws IOException, InterruptedException {
        var testConnectionId = "some connection-id";
        var testMessage = "some message";
        var mockedSession = newMockedSession();
        var mockedBasicRemote = mockedSession.getBasicRemote();
        var circuitBreaker = mock(CircuitBreaker.class);
        var underTest = newConnections(circuitBreaker);
        var connections = underTest.connections();

        connections.register(testConnectionId, mockedSession);
        connections.push(testConnectionId, testMessage);

        assertThat(underTest.sendLockWait().mean(TimeUnit.MICROSECONDS)).isLessThan(TimeUnit.SECONDS.toMicros(1));
        assertThat(underTest.sendLockTimeouts()).isEqualTo(0);
        verify(mockedBasicRemote, timeout(500).times(1)).sendText(testMessage);
        verify(circuitBreaker, times(0)).increment();
        verifyNoMoreInteractions(mockedBasicRemote);
    }

    @Test
    @DisplayName("send-path busy → backpressure failure")
    void pushFailsFastWhenSendPathSaturated() throws IOException, InterruptedException {
        var tcLogger = logger.with("method", "testPushFailsFastWhenSendPathSaturated");
        var testConnectionId = "some connection-id";
        var testMessage1 = "some message";
        var testMessage2 = "some other message";
        var mockedSession = newMockedSession();
        var mockedBasicRemote = mockedSession.getBasicRemote();
        var sendPathDesaturationTimeoutSecs = 3;
        var circuitBreaker = mock(CircuitBreaker.class);
        var underTest = newConnections(
                Duration.ZERO,
                Duration.ofSeconds(sendPathDesaturationTimeoutSecs),
                circuitBreaker, WsConnectionsFixture::createCircuitBreaker);

        underTest.connections().register(testConnectionId, mockedSession);
        try (var blocked = blockSendPath(underTest, testConnectionId, mockedBasicRemote, testMessage1, tcLogger)) {
            var e = Assertions.catchThrowable(() -> underTest.connections().push(testConnectionId, testMessage2));
            tcLogger.debug("Exception from test action: {}", e == null ? null : e.getClass().getSimpleName());
            assertThat(e).isInstanceOf(SendLockWaitTimedOut.class);
            assertThat(((SendLockWaitTimedOut) e).getConnectionId()).isEqualTo(testConnectionId);
        }
        assertThat(underTest.sendLockWait().count()).isEqualTo(2);
        assertThat(underTest.sendLockWait().max(TimeUnit.MICROSECONDS)).isGreaterThan(TimeUnit.SECONDS.toMicros(sendPathDesaturationTimeoutSecs));
        assertThat(underTest.sendLockTimeouts()).isEqualTo(1);
        // The connect-flow breaker: send-path saturation must not leak into it.
        verify(circuitBreaker, times(0)).increment();
    }

    @Test
    @DisplayName("excess send-lock timeouts trip the breaker → later push preempted with RetryAfter")
    void pushPreemptedOnExcessSendLockTimeouts() throws Exception {
        var tcLogger = logger.with("method", "pushPreemptedOnExcessSendLockTimeouts");
        var testConnectionId = "some connection-id";
        var blockingMessage = "some message";
        var mockedSession = newMockedSession();
        var mockedBasicRemote = mockedSession.getBasicRemote();
        var sendLockWaitTimeout = Duration.ofMillis(200);
        var threshold = 1;
        var holdDownPeriod = Duration.ofSeconds(10);
        Supplier<CircuitBreaker> sendLockTimeoutBreakerSupplier =
                () -> new CircuitBreaker(Duration.ofMinutes(1), threshold, holdDownPeriod);
        var underTest = newConnections(
                Duration.ZERO, sendLockWaitTimeout, createCircuitBreaker(), sendLockTimeoutBreakerSupplier);

        underTest.connections().register(testConnectionId, mockedSession);
        try (var blocked = blockSendPath(underTest, testConnectionId, mockedBasicRemote, blockingMessage, tcLogger)) {
            // Drive the breaker past its threshold: each of these contends for the still-held
            // sendLock and times out, incrementing the breaker.
            for (int i = 0; i <= threshold; i++) {
                var message = "timing out " + i;
                var e = Assertions.catchThrowable(() -> underTest.connections().push(testConnectionId, message));
                assertThat(e).isInstanceOf(SendLockWaitTimedOut.class);
            }
            assertThat(underTest.sendLockTimeouts()).isEqualTo(threshold + 1);

            // The breaker is now shedding: this push must be preempted before it ever contends
            // for the sendLock, so it fails with RetryAfter instead of another timeout.
            var preempted = Assertions.catchThrowable(() -> underTest.connections().push(testConnectionId, "one too many"));
            assertThat(preempted).isInstanceOf(RetryAfter.class);
            var retryAfter = (RetryAfter) preempted;
            assertThat(retryAfter.getConnectionId()).isEqualTo(testConnectionId);
            assertThat(retryAfter.getAfterSecs()).isPositive();

            assertThat(underTest.sendLockTimeouts())
                    .as("the preempted push never touched the sendLock")
                    .isEqualTo(threshold + 1);
        }
    }

    // --- helpers ---

    // Blocks the send path on `connectionId` by pushing `blockingMessage`, whose mocked sendText call
    // parks on a latch instead of returning. Returns once the block has actually taken effect, so any
    // push issued after this call contends for the (already held) sendLock. Closing the result releases
    // the block and waits for the blocking push to finish.
    private record BlockedSendPath(ExecutorService executor, CountDownLatch blockingEnd) implements AutoCloseable {
        @Override
        public void close() {
            blockingEnd.countDown(); // the blocker can unblock now.
            executor.close(); // await the blocking push's completion.
        }
    }

    private BlockedSendPath blockSendPath(
            ConnectionsUnderTest underTest,
            String connectionId,
            RemoteEndpoint.Basic mockedBasicRemote,
            String blockingMessage,
            CtxLogger tcLogger) throws IOException, InterruptedException {
        var blockingStart = new CountDownLatch(1);
        var blockingEnd = new CountDownLatch(1);
        doAnswer((Answer<Void>) invocation -> {
            var bLogger = tcLogger.with("thread", Thread.currentThread().threadId());
            blockingStart.countDown(); // safe for the next one to start.
            bLogger.debug("blocker to block...");
            blockingEnd.await(); // wait until released
            return null;
        }).when(mockedBasicRemote).sendText(blockingMessage);

        var executor = Executors.newVirtualThreadPerTaskExecutor();
        executor.submit(() -> {
            underTest.connections().push(connectionId, blockingMessage);
            return null;
        });

        tcLogger.debug("awaiting blocker to block");
        blockingStart.await(); // wait until the blocker is ready for blocking.
        tcLogger.debug("blocker is blocking");
        return new BlockedSendPath(executor, blockingEnd);
    }
}
