package io.github.pdkovacs.wsgw.unit;

import io.github.pdkovacs.wsgw.CircuitBreaker;
import io.github.pdkovacs.wsgw.backpressure.ConnectionGone;
import io.github.pdkovacs.wsgw.socket.WsConnections;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import io.github.pdkovacs.wsgw.unit.support.WsConnectionsFixture;
import jakarta.websocket.CloseReason;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

import static io.github.pdkovacs.wsgw.unit.support.WsConnectionsFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * The connect flow's backpressure (flow=connect, site=registration): the push-before-register race
 * and what happens when the registration lands inside the wait window, or never lands at all.
 * The send-path (flow=push) side of WsConnections lives in {@link PushTest}.
 */
@Timeout(5)
public class ConnectTest {

    private static final CtxLogger logger = CtxLogger.of(WsConnections.class);

    // Population size per arm. Individual runs are races we cannot control; the aggregate over
    // ARM_SIZE runs is what we assert on, so it must be large enough to swamp scheduler noise.
    private static final int ARM_SIZE = 1000;
    // The gap between the two operations. It only has to *bias* the ordering, not guarantee it:
    // the aggregate assertion tolerates a minority of runs landing the "wrong" way. A single gap
    // is slept once per arm (not per run), so wall-clock stays ~one gap regardless of ARM_SIZE.
    private static final Duration ORDERING_GAP = Duration.ofMillis(50);

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

    @Test
    @DisplayName("register lands within registrationWait → send succeeds (race absorbed)")
    void pushToleratesPushBeforeRegister() throws IOException, InterruptedException, ExecutionException {
        var tcLogger = logger.with("method", "testPushToleratesPushBeforeRegister");
        var testConnectionId = "some connection-id";
        var testMessage = "some message";
        var mockedSession = newMockedSession();
        var mockedBasicRemote = mockedSession.getBasicRemote();
        var circuitBreaker = mock(CircuitBreaker.class);
        var underTest = newConnections(circuitBreaker);

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
        assertThat(underTest.registrationTimeouts()).isEqualTo(0);
        verify(mockedBasicRemote, times(1)).sendText(testMessage);
        verify(circuitBreaker, times(0)).increment();
        verifyNoMoreInteractions(mockedBasicRemote);
    }

    @Test
    @DisplayName("register never lands → ConnectionGone thrown, connection terminated, wsgw.registration.timeouts incremented")
    void pushTimesOutWhenRegisterNeverArrives() throws IOException {
        var testConnectionId = "some connection-id";
        var testMessage = "some message";
        var mockedSession = newMockedSession();
        var mockedBasicRemote = mockedSession.getBasicRemote();
        reset(mockedSession); // resets the call getBasicRemote();
        var circuitBreaker = mock(CircuitBreaker.class);
        var underTest = newConnections(Duration.ZERO, WAIT_FOR_SENDMESSAGE_DESATURATION, circuitBreaker, WsConnectionsFixture::createCircuitBreaker);

        try {
            underTest.connections().push(testConnectionId, testMessage);
            throw new AssertionError("Should have thrown a ConnectionGone");
        } catch (Exception e) {
            assertThat(e).isInstanceOf(ConnectionGone.class);
            var connectionGone = (ConnectionGone) e;
            assertThat(connectionGone.getConnectionId()).isEqualTo(testConnectionId);
        }

        assertThat(underTest.registrationWaits()).isEqualTo(0);
        assertThat(underTest.registrationTimeouts()).isEqualTo(1);
        assertThat(underTest.registrationAwaitingTermination()).isEqualTo(1);
        verifyNoMoreInteractions(mockedBasicRemote);
        verifyNoMoreInteractions(mockedSession);

        underTest.connections().register(testConnectionId, mockedSession);

        var captor = ArgumentCaptor.forClass(CloseReason.class);
        verify(mockedSession, times(1)).close(captor.capture());
        assertThat(captor.getValue().getCloseCode()).isEqualTo(CloseReason.CloseCodes.TRY_AGAIN_LATER);
        assertThat(captor.getValue().getReasonPhrase()).isEqualTo("registration too late");
        assertThat(underTest.registrationAwaitingTermination()).isEqualTo(0);
        verify(circuitBreaker, times(1)).increment();
    }

    @Test
    @DisplayName("further pushes to an already-flagged connection are not re-counted")
    void flaggedConnectionCountedOncePerConnection() throws IOException {
        var testConnectionId = "some connection-id";
        var mockedSession = newMockedSession();
        reset(mockedSession); // resets the call getBasicRemote();
        var circuitBreaker = mock(CircuitBreaker.class);
        var underTest = newConnections(
                Duration.ZERO, WAIT_FOR_SENDMESSAGE_DESATURATION, circuitBreaker, WsConnectionsFixture::createCircuitBreaker);

        // The first push flags the connection; the rest are refused from the flag already set.
        // All of them see ConnectionGone, so counting the exception would count this one
        // connection three times over.
        var pushCount = 3;
        for (var i = 0; i < pushCount; i++) {
            var message = "some message " + i;
            var e = Assertions.catchThrowable(() -> underTest.connections().push(testConnectionId, message));
            assertThat(e).isInstanceOf(ConnectionGone.class);
            assertThat(((ConnectionGone) e).getConnectionId()).isEqualTo(testConnectionId);
        }

        assertThat(underTest.registrationTimeouts())
                .as("one connection flagged, however many pushes hit it")
                .isEqualTo(1);
        assertThat(underTest.registrationAwaitingTermination()).isEqualTo(1);
        verify(circuitBreaker, times(1)).increment();

        // ...and the late registration's single decrement still balances the gauge.
        underTest.connections().register(testConnectionId, mockedSession);
        assertThat(underTest.registrationAwaitingTermination())
                .as("the gauge returns to zero, so it did not drift upward")
                .isEqualTo(0);
    }

    // --- helpers ---

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
}
