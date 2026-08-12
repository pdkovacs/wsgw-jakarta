package io.github.pdkovacs.wsgw.socket;

import io.github.pdkovacs.wsgw.backpressure.ConnectionGone;
import io.github.pdkovacs.wsgw.backpressure.SendWaitTimedOut;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Session;

import java.io.IOException;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

class WsConnection {

    private static final CtxLogger logger = CtxLogger.of(WsConnection.class);

    private final CompletableFuture<Session> registeredSession = new CompletableFuture<>();
    private final ReentrantLock sendLock;
    private final AtomicBoolean toTerminate;
    private final String connectionId;

    public WsConnection(String connectionId) {
        sendLock = new ReentrantLock();
        toTerminate = new AtomicBoolean(false);
        this.connectionId = connectionId;
    }

    public void close() throws IOException {
        if (registeredSession.isDone() && !registeredSession.isCompletedExceptionally() && !registeredSession.isCancelled()) {
            registeredSession.getNow(null).close();
        } else {
            registeredSession.cancel(false); // unblock a push parked on get(...)
        }
    }

    public boolean registerSession(Session session) {
        var mLogger = logger.with("connectionId", connectionId).with("method", "registerSession");
        if (toTerminate.get()) {
            mLogger.debug("connection is going to be terminated");
            try {
                session.close(new CloseReason(CloseReason.CloseCodes.TRY_AGAIN_LATER, "registration too late"));
            } catch (IOException e) {
                logger.error("failed to close session in onOpen: {}", e);
                throw new RuntimeException(e);
            }
            return false;
        } else {
            registeredSession.complete(session);
            return true;
        }
    }

    public void sendMessage(String message, Duration pushWaitForRegistrationMs, Duration waitForSendMessageDesaturationMs) throws IOException, InterruptedException, ExecutionException {
        waitForSessionRegistrationToComplete(pushWaitForRegistrationMs);
        sendMessage0(message, waitForSendMessageDesaturationMs);
    }

    private void waitForSessionRegistrationToComplete(Duration pushWaitForRegistrationMs) throws InterruptedException {
        var mLogger = logger.with("connectionId", connectionId).with("method", "getSession");
        Session session;
        try {
            mLogger.debug("waiting for session...");
            session = registeredSession.get(pushWaitForRegistrationMs.toMillis(), MILLISECONDS);
            if (session == null) {
                mLogger.warn("No session");
                throw new NoSuchElementException("No session for connection id %s".formatted(connectionId));
            }
            mLogger.debug("got session");
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (TimeoutException e) {
            toTerminate.set(true);
            throw new ConnectionGone(connectionId);
        }
    }

    private void sendMessage0(String message, Duration waitForSendMessageDesaturationMs) throws IOException, InterruptedException {
        var mLogger = logger.with("connectionId", connectionId).with("method", "sendMessage");
        mLogger.debug("about to wait {} millis for sendLock", waitForSendMessageDesaturationMs);
        if (!sendLock.tryLock(waitForSendMessageDesaturationMs.toMillis(), MILLISECONDS)) {
            mLogger.debug("failed to acquire sendLock", waitForSendMessageDesaturationMs);
            throw new SendWaitTimedOut(connectionId);
        }
        try {
            mLogger.debug("acquired sendLock");
            registeredSession.getNow(null).getBasicRemote().sendText(message);
        } finally {
            sendLock.unlock();
        }
    }

}
