package io.github.pdkovacs.wsgw.appward;

import io.github.pdkovacs.wsgw.logging.CtxLogger;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class Dispatcher {
    private static final CtxLogger logger = CtxLogger.of(Dispatcher.class);

    private volatile Thread workerThread;

    interface Dispatch {
        void send();
    }

    public static final Dispatch POISON = new Dispatch() {
        @Override
        public void send() {
        }
    };

    interface ErrorChannel {
        void report(Throwable throwable);
    }

    private final BlockingQueue<Dispatch> queue;
    private final ErrorChannel errorChannel;

    Dispatcher(int queueSize, ErrorChannel errorChannel) {
        queue = new LinkedBlockingQueue<>(queueSize);
        this.errorChannel = errorChannel;
    }

    void accept(Dispatch dispatch) {
        try {
            queue.put(dispatch);
        } catch (InterruptedException e) {
            logger.info("{} interrupted in accept", this);
        }
    }

    void start(String connectionId) {
        workerThread = Thread.ofVirtual().name("dispatcher + " + connectionId).start(this::run);
    }

    private void run() {
        var mLogger = logger.with("method", "run").with("thread", Thread.currentThread().getName());
        mLogger.info("Running");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                var dispatch = queue.take();
                if (dispatch == POISON) {
                    mLogger.debug("POISON received");
                    break;
                }
                dispatch.send();
            } catch (InterruptedException e) {
                mLogger.debug("Dispatcher got interrupted");
                break;
            } catch (Throwable t) {
                mLogger.error("Dispatcher got error", t);
                errorChannel.report(t);
            }
        }
        mLogger.info("Finishing... interrupted: {}", Thread.currentThread().isInterrupted());
    }

    public void join(Duration timeout) {
        try {
            workerThread.join(timeout);
        } catch (InterruptedException e) {
            logger.info("{} interrupted in join", workerThread.getName());
        }
    }

    public boolean isDefunct() {
        return !workerThread.isAlive();
    }

    @Override
    public String toString() {
        return "Dispatcher{threadName='" + Thread.currentThread().getName() + "'}";
    }
}
