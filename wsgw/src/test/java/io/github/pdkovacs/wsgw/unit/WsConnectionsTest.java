package io.github.pdkovacs.wsgw.unit;

import io.github.pdkovacs.wsgw.clientside.WsConnections;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class WsConnectionsTest {
    @Test
    @DisplayName("the happy path")
    void testLonelyPushSendsMessageOverRegisteredSession() throws IOException, InterruptedException {
        var testConnectionId = "some connection-id";
        var testMessage = "some message";
        var mockedSession = newMockedSession();
        var mockedBasicRemote = mockedSession.getBasicRemote();
        var connections = new WsConnections(Duration.ofSeconds(5));

        connections.register(testConnectionId, mockedSession);
        connections.push(testConnectionId, testMessage);

        verify(mockedBasicRemote, timeout(500).times(1)).sendText(testMessage);
        verifyNoMoreInteractions(mockedBasicRemote);
    }

    private Session newMockedSession() {
        var session = mock(Session.class);
        var basicRemote = mock(RemoteEndpoint.Basic.class);
        when(session.getBasicRemote()).thenReturn(basicRemote);
        return session;
    }

    private record ConnectionsUnderTest(WsConnections connections, AtomicInteger pushWaitsOnRegistration) {}

    private ConnectionsUnderTest newConnections() {
        var counter = new AtomicInteger(0);
        var timeouts = new WsConnections.Timeouts() {
            @Override
            public Duration getPushWaitForRegistration() {
                return WAIT_FOR_REGISTRATION;
            }

            @Override
            public Duration getWaitForSendMessageDesaturation() {
                return Duration.ofSeconds(1);
            }
        };
        var metrics = new WsConnections.Metrics() {
            @Override
            public void incPushWaitsOnRegistrationCount() {
                counter.incrementAndGet();
            }
        };
        return new ConnectionsUnderTest(new WsConnections(timeouts, metrics), counter);
    }

    // Population size per arm. Individual runs are races we cannot control; the aggregate over
    // ARM_SIZE runs is what we assert on, so it must be large enough to swamp scheduler noise.
    private static final int ARM_SIZE = 1000;
    // The gap between the two operations. It only has to *bias* the ordering, not guarantee it:
    // the aggregate assertion tolerates a minority of runs landing the "wrong" way. A single gap
    // is slept once per arm (not per run), so wall-clock stays ~one gap regardless of ARM_SIZE.
    private static final Duration ORDERING_GAP = Duration.ofMillis(50);
    private static final Duration WAIT_FOR_REGISTRATION = Duration.ofSeconds(5);

    @Test
    @DisplayName("pushWaitsOnRegistrationCount tracks push-before-register ordering across a population")
    void testPushWaitsOnRegistrationCountTracksOrdering() throws InterruptedException, ExecutionException {
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
    void testManyEarlyPushesCountAsOneRacedConnection() throws InterruptedException, ExecutionException {
        var underTest = newConnections();
        var connections = underTest.connections();
        var counter = underTest.pushWaitsOnRegistration();

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
        Assertions.assertThat(counter.get())
            .as("the fan-out of early pushes on one connection counts as a single raced connection")
            .isEqualTo(1);
    }

    // Runs one population arm and returns how many pushes recorded a wait-on-registration.
    // registerFirst == false: submit all pushes, wait one gap, then register -> push-arrives-first.
    // registerFirst == true : register all, wait one gap, then submit pushes -> register-arrives-first.
    private int runArm(boolean registerFirst) throws InterruptedException, ExecutionException {
        var underTest = newConnections();
        var connections = underTest.connections();
        var counter = underTest.pushWaitsOnRegistration();

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
        return counter.get();
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
     public void testPushToleratesPushBeforeRegister() throws IOException, InterruptedException, ExecutionException {
        var testConnectionId = "some connection-id";
        var testMessage = "some message";
        var mockedSession = newMockedSession();
        var mockedBasicRemote = mockedSession.getBasicRemote();
        var connections = new WsConnections(Duration.ofSeconds(5));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit((Callable<Void>) () -> {
                connections.push(testConnectionId, testMessage);
                return null;
            });
            Thread.sleep();
            connections.register(testConnectionId, mockedSession);
            future.get();
        }

        verify(mockedBasicRemote, timeout(500).times(1)).sendText(testMessage);
        verifyNoMoreInteractions(mockedBasicRemote);
     }

    // @Test
    // // register never lands → registration-timeout failure.
    // public void testPushTimesOutWhenRegisterNeverArrives() {}
    // @Test
    // // send-path busy → backpressure failure.
    // public void testPushFailsFastWhenSendPathSaturated() {}

}
