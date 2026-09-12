package io.github.pdkovacs.wsgw.appward;

import io.github.pdkovacs.wsgw.logging.CtxLogger;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Dispatcher {
    private static final CtxLogger logger = CtxLogger.of(Dispatcher.class);

    public record QueueParams(int queueSize, Duration relayEnqueueTimeout) {}

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

    record Meters(AtomicInteger relayBufferDepth, AtomicInteger relayBufferHWMark) {
        static Meters create(MeterRegistry meterRegistry) {
            var relayBufferDepth = new AtomicInteger(0);
            Gauge.builder("wsgw.relay.buffer.depth", relayBufferDepth, AtomicInteger::get)
                    .tag("flow", "relay")
                    .tag("site", "client_to_gw")
                    .register(meterRegistry);
            var relayBufferHWMark = new AtomicInteger(0);
            Gauge.builder("wsgw.relay.buffer.hwmark", relayBufferHWMark, AtomicInteger::get)
                    .tag("flow", "relay")
                    .tag("site", "client_to_gw")
                    .register(meterRegistry);

            return new Meters(relayBufferDepth, relayBufferHWMark);
        }
    }

    private final BlockingQueue<Dispatch> queue;
    private final ErrorChannel errorChannel;
    private final Duration relayEnqueueTimeout;
    private final Meters meters;

    Dispatcher(QueueParams queueParams, ErrorChannel errorChannel, MeterRegistry meterRegistry) {
        queue = new LinkedBlockingQueue<>(queueParams.queueSize);
        this.errorChannel = errorChannel;
        this.relayEnqueueTimeout = queueParams.relayEnqueueTimeout;
        this.meters = Meters.create(meterRegistry);
    }

    void accept(Dispatch dispatch) {
        try {
            meters.relayBufferDepth.set(queue.size());
            incrementToTheMax(meters.relayBufferHWMark, queue.size());
            queue.offer(dispatch, relayEnqueueTimeout.toNanos(), TimeUnit.NANOSECONDS);
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

    private static boolean incrementToTheMax(AtomicInteger atomicInt, int max) {
        while (true) {
            int value = atomicInt.get();
            if (value >= max) {
                // The counter has already reached max, so don't increment it.
                return false;
            }
            if (atomicInt.compareAndSet(value, value+1)) {
                // If we reach here, the atomic integer still had the value "value";
                // and so we incremented it.
                return true;
            }
            // If we reach here, some other thread atomically updated the value.
            // Rats! Loop, and try to increment of again.
        }
    }
}
