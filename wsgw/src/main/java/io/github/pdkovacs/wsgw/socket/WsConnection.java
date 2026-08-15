package io.github.pdkovacs.wsgw.socket;

import io.github.pdkovacs.wsgw.backpressure.ConnectionGone;
import io.github.pdkovacs.wsgw.backpressure.SendWaitTimedOut;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Session;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

class WsConnection {

    private static final CtxLogger logger = CtxLogger.of(WsConnection.class);

    private final String connectionId;
    private final Object registrationLock = new Object();
    private final ReentrantLock sendLock;

    private volatile Session registeredSession;
    private boolean registrationTooLate = false;

    public WsConnection(String connectionId) {
        sendLock = new ReentrantLock();
        this.connectionId = connectionId;
    }

    public void close() throws IOException {
        if (registeredSession != null) {
            registeredSession.close();
        }
        registrationLock.notify();
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
                registrationLock.notify();
            }
        }
    }

    public void sendMessage(String message, Timeouts timeouts) throws IOException, InterruptedException, ExecutionException {
        waitForSessionRegistrationToComplete(timeouts.pushWaitForRegistration());
        sendMessageSessionAssumed(message, timeouts.pushWaitForSendMessageDesaturation());
    }

    private void waitForSessionRegistrationToComplete(Duration pushWaitForRegistration) throws InterruptedException {
        var mLogger = logger.with("connectionId", connectionId).with("method", "waitForSessionRegistrationToComplete");
        if (registeredSession != null) {
            mLogger.debug("Registered connection found");;
            return;
        }

        synchronized (registrationLock) {
            Session session;
            if (!pushWaitForRegistration.isZero()) { // Mimic the semantics of Future.get(...) for zero
                mLogger.debug("waiting for session...");
                registrationLock.wait(pushWaitForRegistration.toMillis());
            }
            if (registeredSession == null) {
                mLogger.warn("No session");
                registrationTooLate = true;
                throw new ConnectionGone(connectionId);
            }
            mLogger.debug("got session");
        }
    }

    private void sendMessageSessionAssumed(String message, Duration waitForSendMessageDesaturation) throws IOException, InterruptedException {
        var mLogger = logger.with("connectionId", connectionId).with("method", "sendMessage");
        if (registeredSession == null) {
            mLogger.warn("No registered session");
            throw new IllegalStateException("No registered session");
        }
        mLogger.debug("about to wait {} for sendLock", waitForSendMessageDesaturation);
        if (!sendLock.tryLock(waitForSendMessageDesaturation.toMillis(), MILLISECONDS)) {
            mLogger.debug("failed to acquire sendLock", waitForSendMessageDesaturation);
            throw new SendWaitTimedOut(connectionId);
        }
        try {
            mLogger.debug("acquired sendLock");
            registeredSession.getBasicRemote().sendText(message);
        } finally {
            sendLock.unlock();
        }
    }

}
