package io.github.pdkovacs.wsgw.appward;

import io.github.pdkovacs.wsgw.logging.CtxLogger;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class Dispatcher {
    private static final CtxLogger logger = CtxLogger.of(Dispatcher.class);

    interface Dispatch {
        void send();
    }

    interface ErrorChannel {
        void report(Throwable throwable);
    }

    private final BlockingQueue<Dispatch> queue;
    private final ErrorChannel errorChannel;

    private volatile boolean done;

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
        Thread.ofVirtual().name("dispatcher + " + connectionId).start(this::run);
    }

    private void run() {
        var mLogger = logger.with("method", "run").with("thread", Thread.currentThread().getName());
        mLogger.info("Running");
        while (!isDone() && !Thread.currentThread().isInterrupted()) {
            try {
                var dispatch = queue.take();
                dispatch.send();
            } catch (InterruptedException e) {
                mLogger.debug("Dispatcher got interrupted");
                break;
            } catch (Throwable t) {
                mLogger.error("Dispatcher got error", t);
                errorChannel.report(t);
                break;
            }
        }
        mLogger.info("Finishing... interrupted: {}", Thread.currentThread().isInterrupted());
    }

    private boolean isDone() {
        return done;
    }

    void setDone() {
        this.done = true;
    }

    @Override
    public String toString() {
        return "Dispatcher{threadName='" + Thread.currentThread().getName() + "'}";
    }
}
