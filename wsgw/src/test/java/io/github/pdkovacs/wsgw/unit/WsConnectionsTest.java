package io.github.pdkovacs.wsgw.unit;

import io.github.pdkovacs.wsgw.backpressure.ConnectionGone;
import io.github.pdkovacs.wsgw.backpressure.SendWaitTimedOut;
import io.github.pdkovacs.wsgw.socket.Timeouts;
import io.github.pdkovacs.wsgw.socket.WsConnections;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.websocket.CloseReason;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

@Timeout(5)
public class WsConnectionsTest {

    private static final CtxLogger logger = CtxLogger.of(WsConnections.class);

    // Population size per arm. Individual runs are races we cannot control; the aggregate over
    // ARM_SIZE runs is what we assert on, so it must be large enough to swamp scheduler noise.
    private static final int ARM_SIZE = 1000;
    // The gap between the two operations. It only has to *bias* the ordering, not guarantee it:
    // the aggregate assertion tolerates a minority of runs landing the "wrong" way. A single gap
    // is slept once per arm (not per run), so wall-clock stays ~one gap regardless of ARM_SIZE.
    private static final Duration ORDERING_GAP = Duration.ofMillis(50);
    private static final Duration WAIT_FOR_REGISTRATION = Duration.ofSeconds(2);
    private static final Duration WAIT_FOR_SENDMESSAGE_DESATURAITON = Duration.ofSeconds(1);

    private Session newMockedSession() {
        var session = mock(Session.class);
        var basicRemote = mock(RemoteEndpoint.Basic.class);
        when(session.getBasicRemote()).thenReturn(basicRemote);
        return session;
    }

    private record ConnectionsUnderTest(WsConnections connections, MeterRegistry registry) {
        // Eagerly registered in the WsConnections ctor, so this read succeeds (returning 0) even in
        // arms where no push ever raced -- the count, not a MeterNotFoundException, is the signal.
        int registrationWaits() {
            return (int) registry.get("wsgw.registration.waits").tag("leg", "push").counter().count();
        }

        int registrationTimeoutFlagged() {
            return (int) registry.get("wsgw.registration.timeout.flagged").tag("leg", "push").counter().count();
        }

        int registrationTimeoutAbondoned() {
            return (int) registry.get("wsgw.registration.timeout.abandoned").tag("leg", "push").gauge().value();
        }

        Timer pushSendLogWait() {
            return registry.get("wsgw.send_lock.wait").tag("leg", "push").timer();
        }

        int pushSendLockTimeouts() {
          return (int) registry.get("wsgw.send_lock.timeouts").tag("leg", "push").counter().count();
        }
    }

    private ConnectionsUnderTest newConnections() {
        return newConnections(WAIT_FOR_REGISTRATION, WAIT_FOR_SENDMESSAGE_DESATURAITON);
    }

    private ConnectionsUnderTest newConnections(Duration pushWaitForRegistration) {
        return newConnections(pushWaitForRegistration, WAIT_FOR_SENDMESSAGE_DESATURAITON);
    }

    private ConnectionsUnderTest newConnections(Duration pushWaitForRegistration, Duration waitForSendMessageDesaturation) {
        var registry = new SimpleMeterRegistry();
        var timeouts = new Timeouts(pushWaitForRegistration, waitForSendMessageDesaturation);
        return new ConnectionsUnderTest(new WsConnections(timeouts, registry), registry);
    }

    @Test
    @DisplayName("the happy path")
    void lonelyPushSendsMessageOverRegisteredSession() throws IOException, InterruptedException {
        var testConnectionId = "some connection-id";
        var testMessage = "some message";
        var mockedSession = newMockedSession();
        var mockedBasicRemote = mockedSession.getBasicRemote();
        var underTest = newConnections();
        var connections = underTest.connections();

        connections.register(testConnectionId, mockedSession);
        connections.push(testConnectionId, testMessage);

        assertThat(underTest.pushSendLogWait().mean(TimeUnit.MICROSECONDS)).isLessThan(TimeUnit.SECONDS.toMicros(1));
        assertThat(underTest.pushSendLockTimeouts()).isEqualTo(0);
        verify(mockedBasicRemote, timeout(500).times(1)).sendText(testMessage);
        verifyNoMoreInteractions(mockedBasicRemote);
    }

    @Test
    @DisplayName("pushWaitsOnRegistrationCount tracks push-before-register ordering across a population")
    void pushWaitsOnRegistrationCountTracksOrdering() throws InterruptedException, ExecutionException {
        // We do not assume the injected gap deterministically fixes the ordering (that would be an
        // assumption about the runtime's timing properties). We only assume it *biases* it, and
        // assert that the counter separates the two populations by a margin only broken code could
        // breach -- not a thin significance threshold that scheduler noise could tip either way.
        int registerAfterPush = runArm(/* registerFirst */ false);
        int registerBeforePush = runArm(/* registerFirst */ true);

        Assertions.assertThat(registerAfterPush)
                .as("pushes that arrived before their registration and had to wait")
                .isGreaterThan((int) (0.75 * ARM_SIZE));
        Assertions.assertThat(registerBeforePush)
                .as("pushes that found their registration already present (no wait)")
                .isLessThan((int) (0.25 * ARM_SIZE));
    }

    @Test
    @DisplayName("many pushes before register on one connection count as a single raced connection")
    void manyEarlyPushesCountAsOneRacedConnection() throws InterruptedException, ExecutionException {
        var underTest = newConnections();
        var connections = underTest.connections();

        var mockedSession = newMockedSession();

        var connectionId = "shared-connection";
        int earlyPushes = 200;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pushes = new ArrayList<Future<?>>(earlyPushes);
            for (int i = 0; i < earlyPushes; i++) {
                pushes.add(executor.submit((Callable<Void>) () -> {
                    connections.push(connectionId, "some message");
                    return null;
                }));
            }
            Thread.sleep(ORDERING_GAP.toMillis());
            connections.register(connectionId, mockedSession);
            awaitAll(pushes);
        }

        // The metric measures race incidence (connections that hit the push-before-register window),
        // not parked-wait volume, so a fan-out of early pushes on one connection must still count as
        // one. This assertion is exact and needs only the weak, robust precondition that *at least
        // one* of the many pushes lands before register -- the rest dedup onto the same holder by
        // construction, so there is nothing to bias into a wide margin.
        Assertions.assertThat(underTest.registrationWaits())
                .as("the fan-out of early pushes on one connection counts as a single raced connection")
                .isEqualTo(1);
    }

    // Runs one population arm and returns how many pushes recorded a wait-on-registration.
    // registerFirst == false: submit all pushes, wait one gap, then register -> push-arrives-first.
    // registerFirst == true : register all, wait one gap, then submit pushes -> register-arrives-first.
    private int runArm(boolean registerFirst) throws InterruptedException, ExecutionException {
        var underTest = newConnections();
        var connections = underTest.connections();

        var mockedSession = newMockedSession();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            if (registerFirst) {
                for (int i = 0; i < ARM_SIZE; i++) {
                    connections.register("conn-" + i, mockedSession);
                }
                Thread.sleep(ORDERING_GAP.toMillis());
                awaitAll(submitPushes(executor, connections));
            } else {
                var pushes = submitPushes(executor, connections);
                Thread.sleep(ORDERING_GAP.toMillis());
                for (int i = 0; i < ARM_SIZE; i++) {
                    connections.register("conn-" + i, mockedSession);
                }
                awaitAll(pushes);
            }
        }
        return underTest.registrationWaits();
    }

    private List<Future<?>> submitPushes(java.util.concurrent.ExecutorService executor, WsConnections connections) {
        var pushes = new ArrayList<Future<?>>(ARM_SIZE);
        for (int i = 0; i < ARM_SIZE; i++) {
            var connectionId = "conn-" + i;
            pushes.add(executor.submit((Callable<Void>) () -> {
                connections.push(connectionId, "some message");
                return null;
            }));
        }
        return pushes;
    }

    private void awaitAll(List<Future<?>> futures) throws InterruptedException, ExecutionException {
        for (var future : futures) {
            future.get();
        }
    }

    @Test
    @DisplayName("register lands within registrationWait → send succeeds (race absorbed)")
    void pushToleratesPushBeforeRegister() throws IOException, InterruptedException, ExecutionException {
        var tcLogger = logger.with("method", "testPushToleratesPushBeforeRegister");
        var testConnectionId = "some connection-id";
        var testMessage = "some message";
        var mockedSession = newMockedSession();
        var mockedBasicRemote = mockedSession.getBasicRemote();
        var underTest = newConnections();

        int iterationCount = 0;
        while (++iterationCount < 1000) {
            tcLogger.debug("Iteration {}", iterationCount);
            final var fUnderTest = underTest;
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var future = executor.submit((Callable<Void>) () -> {
                    fUnderTest.connections().push(testConnectionId, testMessage);
                    return null;
                });
                Thread.sleep(ORDERING_GAP.toMillis());
                underTest.connections().register(testConnectionId, mockedSession);
                future.get();
            }

            if (underTest.registrationWaits() >= 1) {
                break;
            }
            mockedSession = newMockedSession();
            mockedBasicRemote = mockedSession.getBasicRemote();
            underTest = newConnections();
        }

        assertThat(iterationCount).isLessThan(1000);
        assertThat(underTest.registrationWaits()).isEqualTo(1);
        assertThat(underTest.registrationTimeoutFlagged()).isEqualTo(0);
        verify(mockedBasicRemote, times(1)).sendText(testMessage);
        verifyNoMoreInteractions(mockedBasicRemote);
    }

    @Test
    @DisplayName("register never lands → ConnectionGone thrown, connection terminated, wsgw.registration.timeout.flagged incremented")
    void pushTimesOutWhenRegisterNeverArrives() throws IOException {
        var testConnectionId = "some connection-id";
        var testMessage = "some message";
        var mockedSession = newMockedSession();
        var mockedBasicRemote = mockedSession.getBasicRemote();
        reset(mockedSession); // resets the call getBasicRemote();
        var underTest = newConnections(Duration.ZERO);

        try {
            underTest.connections().push(testConnectionId, testMessage);
            throw new AssertionError("Should have thrown a ConnectionGone");
        } catch (Exception e) {
            assertThat(e).isInstanceOf(ConnectionGone.class);
            var connectionGone = (ConnectionGone) e;
            assertThat(connectionGone.getConnectionId()).isEqualTo(testConnectionId);
        }

        assertThat(underTest.registrationWaits()).isEqualTo(0);
        assertThat(underTest.registrationTimeoutFlagged()).isEqualTo(1);
        assertThat(underTest.registrationTimeoutAbondoned()).isEqualTo(1);
        verifyNoMoreInteractions(mockedBasicRemote);
        verifyNoMoreInteractions(mockedSession);

        underTest.connections().register(testConnectionId, mockedSession);

        var captor = ArgumentCaptor.forClass(CloseReason.class);
        verify(mockedSession, times(1)).close(captor.capture());
        assertThat(captor.getValue().getCloseCode()).isEqualTo(CloseReason.CloseCodes.TRY_AGAIN_LATER);
        assertThat(captor.getValue().getReasonPhrase()).isEqualTo("registration too late");
        assertThat(underTest.registrationTimeoutAbondoned()).isEqualTo(0);
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
        var underTest = newConnections(Duration.ZERO, Duration.ofSeconds(sendPathDesaturationTimeoutSecs));

        var blockingStart = new CountDownLatch(1);
        var blockingEnd = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                underTest.connections().register(testConnectionId, mockedSession);
                doAnswer((Answer<Void>) invocation -> {
                    var logger = tcLogger.with("thread", Thread.currentThread().threadId());
                    blockingStart.countDown(); // safe for the next one to start.
                    logger.debug("blocker to sleep...");
                    logger.debug("blocker to block...");
                    blockingEnd.await(); // wait until the other finishes
                    return null;
                }).when(mockedBasicRemote).sendText(testMessage1);
                executor.submit(() -> {
                    underTest.connections().push(testConnectionId, testMessage1);
                    return null;
                });

                tcLogger.debug("awaiting blocker to block");
                blockingStart.await(); // wait until the previous one is ready for blocking.
                tcLogger.debug("blocker is blocking");
                underTest.connections().push(testConnectionId, testMessage2);
                throw new AssertionError("Should have thrown a SendWaitTimedOut");
            } catch (Exception e) {
                tcLogger.debug("Exception from test action: {}", e.getClass().getSimpleName());
                assertThat(e).isInstanceOf(SendWaitTimedOut.class);
                var sbe = (SendWaitTimedOut) e;
                assertThat(sbe.getConnectionId()).isEqualTo(testConnectionId);
            } finally {
                blockingEnd.countDown(); // the previous one can unblock now.
            }
            assertThat(underTest.pushSendLogWait().count()).isEqualTo(2);
            assertThat(underTest.pushSendLogWait().max(TimeUnit.MICROSECONDS)).isGreaterThan(TimeUnit.SECONDS.toMicros(sendPathDesaturationTimeoutSecs));
            assertThat(underTest.pushSendLockTimeouts()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("sendMessage per-connection in-flight count")
    void sendMessageInflightCount() throws IOException {

    }
}
