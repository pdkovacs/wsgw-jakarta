package io.github.pdkovacs.wsgw.socket;

import io.github.pdkovacs.wsgw.CircuitBreaker;
import io.github.pdkovacs.wsgw.backpressure.ConnectionGone;
import io.github.pdkovacs.wsgw.backpressure.RetryAfter;
import io.github.pdkovacs.wsgw.backpressure.SendLockWaitTimedOut;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Session;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class WsConnection {

    private static final CtxLogger logger = CtxLogger.of(WsConnection.class);

    record Metrics(Timer sendLockWait, Counter sendLockTimeouts) {

    }

    /**
     * Where this connection sits in its registration lifecycle. The three values are mutually
     * exclusive and exhaustive, so summing the connections in each gives the size of the registry
     * -- which is what makes them tag values on one gauge rather than three gauges.
     */
    public enum State {
        AWAITING_REGISTRATION("awaiting_registration"),
        REGISTERED("registered"),
        AWAITING_TERMINATION("awaiting_termination");

        // Spelled out rather than derived from name(), so that renaming a constant cannot
        // silently rename a published metric's tag value (docs/backpressure.md 2.2).
        private final String tagValue;

        State(String tagValue) {
            this.tagValue = tagValue;
        }

        public String tagValue() {
            return tagValue;
        }
    }

    private final Metrics metrics;
    private final CircuitBreaker sendLockTimeoutBreaker;
    // Called exactly once, by the thread that flags this connection for termination. Every push
    // that arrives afterwards is refused from the already-set flag and reports nothing, so what
    // this reports is the flagging of one connection, not the volume of pushes that hit it.
    private final Runnable onRegistrationTimeout;
    private final String connectionId;
    private final Object registrationLock = new Object();
    private final ReentrantLock sendLock;

    private volatile Session registeredSession;
    // Written only under registrationLock, but read by the metrics scrape thread, which holds
    // nothing -- volatile is what makes that read defined rather than merely usually right.
    private volatile boolean registrationTooLate = false;

    public WsConnection(
            String connectionId,
            Metrics metrics,
            CircuitBreaker sendLockTimeoutBreaker,
            Runnable onRegistrationTimeout) {
        sendLock = new ReentrantLock();
        this.connectionId = connectionId;
        this.metrics = metrics;
        this.sendLockTimeoutBreaker = sendLockTimeoutBreaker;
        this.onRegistrationTimeout = onRegistrationTimeout;
    }

    // Derived, not tallied: the state is whatever the two fields below say it is, so there is no
    // increment/decrement pair that could drift away from the registry it claims to describe.
    // registeredSession is checked first because registerSession only ever sets it when the
    // connection was not already flagged, so the two can never both be true.
    State state() {
        if (registeredSession != null) {
            return State.REGISTERED;
        }
        return registrationTooLate ? State.AWAITING_TERMINATION : State.AWAITING_REGISTRATION;
    }

    public void closeSession() throws IOException {
        if (registeredSession != null) {
            registeredSession.close();
        }
    }

    public void disconnect() {
        synchronized (registrationLock) {
            registrationLock.notifyAll();
        }
    }

    public boolean registerSession(Session session) {
        var mLogger = logger.with("connectionId", connectionId).with("method", "registerSession");
        synchronized (registrationLock) {
            try {
                if (registrationTooLate) {
                    mLogger.debug("connection is going to be terminated");
                    try {
                        session.close(new CloseReason(CloseReason.CloseCodes.TRY_AGAIN_LATER, "registration too late"));
                    } catch (IOException e) {
                        logger.error("failed to close session in onOpen: {}", e);
                        throw new RuntimeException(e);
                    }
                    return false;
                } else {
                    registeredSession = session;
                    return true;
                }
            } finally {
                registrationLock.notifyAll();
            }
        }
    }

    public void sendMessage(String message, Timeouts timeouts) throws IOException, InterruptedException, ExecutionException {
        var remaining = sendLockTimeoutBreaker.jitteredRemaining();
        logger.debug("Sending message to connection: {} {}", sendLockTimeoutBreaker, remaining);
        if (remaining != null) {
            throw new RetryAfter(connectionId, remaining.toSeconds());
        }
        waitForSessionRegistrationToComplete(timeouts.registrationWaitTimeout());
        sendMessageSessionAssumed(message, timeouts.sendLockWaitTimeout());
    }

    private void waitForSessionRegistrationToComplete(Duration registrationWaitTimeout) throws InterruptedException {
        var mLogger = logger.with("connectionId", connectionId).with("method", "waitForSessionRegistrationToComplete");

        if (registeredSession != null) {
            mLogger.debug("Registered connection found");
            return;
        }

        if (registrationTooLate) {
            mLogger.debug("registrationTooLate already established");
            throw new ConnectionGone(connectionId);
        }

        synchronized (registrationLock) {
            var budget = registrationWaitTimeout;
            while (budget.isPositive() && registeredSession == null) {
                mLogger.debug("waiting for session...");
                var start = System.nanoTime();
                registrationLock.wait(budget.toMillis());
                var elapsed = System.nanoTime() - start;
                budget = Duration.ofNanos(budget.toNanos() - elapsed);
            }
            if (registeredSession == null) {
                mLogger.warn("No session");
                registrationTooLate = true;
                // Under registrationLock, and registrationTooLate is set nowhere else, so this
                // runs once per flagged connection -- see the field's comment.
                onRegistrationTimeout.run();
                throw new ConnectionGone(connectionId);
            }
            mLogger.debug("got session");
        }
    }

    private void sendMessageSessionAssumed(String message, Duration sendLockWaitTimeout) throws IOException, InterruptedException {
        var mLogger = logger.with("connectionId", connectionId).with("method", "sendMessage");
        if (registeredSession == null) {
            mLogger.warn("No registered session");
            throw new IllegalStateException("No registered session");
        }
        mLogger.debug("about to wait for sendLock");
        var start = System.nanoTime();
        try {
            if (!sendLock.tryLock(sendLockWaitTimeout.toMillis(), MILLISECONDS)) {
                mLogger.debug("failed to acquire sendLock", sendLockWaitTimeout);
                metrics.sendLockTimeouts.increment();
                sendLockTimeoutBreaker.increment();
                throw new SendLockWaitTimedOut(connectionId);
            }
        } finally {
            var end = System.nanoTime();
            metrics.sendLockWait.record(Duration.ofNanos(end - start));
        }
        try {
            mLogger.debug("acquired sendLock");
            registeredSession.getBasicRemote().sendText(message);
        } finally {
            sendLock.unlock();
        }
    }

}
