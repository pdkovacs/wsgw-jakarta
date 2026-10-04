package io.github.pdkovacs.wsgw.appward;

import io.github.pdkovacs.wsgw.logging.CtxLogger;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.IntPredicate;

public class Relays {
    private static final CtxLogger logger = CtxLogger.of(Relays.class);

    public record RetryParams(int maxRetries, Duration retryInterval, double retryBudget, Duration retryBudgetWindow) {}

    private final Request appwardRequest;
    private final ConcurrentHashMap<String, Relay> relays = new ConcurrentHashMap<>();
    private final Dispatcher.QueueParams queueParams;
    private final Duration responseTimeout;
    private final Relay.Meters relayMeters;
    private final RetryParams retryParams;
    private final RetryBudget retryBudget;

    public Relays(Request appwardRequest, Dispatcher.QueueParams queueParams,
                  Duration responseTimeout, RetryParams retryParams, MeterRegistry meterRegistry) {
        this.appwardRequest = appwardRequest;
        this.queueParams = queueParams;
        this.responseTimeout = responseTimeout;
        this.retryParams = retryParams;
        relayMeters = createRelayMeters(meterRegistry);
        retryBudget = createRetryBudget(retryParams, meterRegistry);
    }

    public Relay createRelay(Map<String, List<String>> requestHeaders, String connectionId) {
        var relay = new Relay(appwardRequest, requestHeaders, connectionId, queueParams, responseTimeout,
                new Relay.RetryParams(retryParams.maxRetries, retryParams.retryInterval, retryBudget),
                relayMeters, () -> {
                    relays.remove(connectionId);
                });
        relays.put(connectionId, relay);
        return relay;
    }

    public Relay get(String connectionId) {   // retire from registry, hand it back
        return relays.get(connectionId);
    }

    public void stop() {
        for (Relay relay : relays.values()) {
            relay.join(Duration.ofSeconds(5));
        }
        appwardRequest.stop();
    }

    public Request appwardRequest() {
        return appwardRequest;
    }

    private Relay.Meters createRelayMeters(MeterRegistry meterRegistry) {

        BiConsumer<String, IntPredicate> registerFillBand = (band, inBand) -> {
            Gauge.builder("wsgw.relay.buffer.connections", relays,
                            m -> m.values().stream().mapToInt(Relay::queueSize).filter(inBand).count())
                    .tag("flow", "relay").tag("site", "client_to_gw").tag("fill", band)
                    .register(meterRegistry);
        };

        var b = queueParams.queueSize();
        registerFillBand.accept("empty", d -> d == 0);
        registerFillBand.accept("low",   d -> d > 0 && d <= b / 2);
        registerFillBand.accept("high",  d -> d > b / 2 && d < b);
        registerFillBand.accept("full",  d -> d >= b);

        Timer relayEnqueueWaitTime = Timer.builder("wsgw.relay.enqueue.wait")
                .tag("flow", "relay")
                .tag("site", "client_to_gw").register(meterRegistry);
        Counter relayEnqueueDrops = Counter.builder("wsgw.relay.enqueue.drops")
                .tag("flow", "relay")
                .tag("site", "client_to_gw").register(meterRegistry);

        Timer relayLatency = Timer.builder("wsgw.relay.latency")
                .tag("flow", "relay")
                .tag("site", "gw_to_app").register(meterRegistry);
        Counter relayAttempts = Counter.builder("wsgw.relay.attempts")
                .tag("flow", "relay")
                .tag("site", "gw_to_app").register(meterRegistry);
        Counter relayRetries = Counter.builder("wsgw.relay.retries")
                .tag("flow", "relay")
                .tag("site", "gw_to_app").register(meterRegistry);
        Counter retryExhaustions = Counter.builder("wsgw.relay.retry.exhaustions")
                .tag("flow", "relay")
                .tag("site", "gw_to_app").register(meterRegistry);

        return new Relay.Meters(relayEnqueueWaitTime,
                relayLatency, relayAttempts, relayRetries, relayEnqueueDrops, retryExhaustions);
    }

    static class RetryBudgetDropCountIncrementor implements Runnable {
        private final Counter counter;

        RetryBudgetDropCountIncrementor(MeterRegistry meterRegistry) {
            counter = Counter.builder("wsgw.relay.retry.budget.drops")
                    .tag("flow", "relay")
                    .tag("site", "gw_to_app").register(meterRegistry);
        }

        @Override
        public void run() {
            counter.increment();
        }
    }

    private RetryBudget createRetryBudget(RetryParams retryParams, MeterRegistry meterRegistry) {
        return new RetryBudget(retryParams.retryBudget, retryParams.retryBudgetWindow,
                () -> relayMeters.relayAttempts().count(),
                new RetryBudget.RetryCounter() {
                    @Override
                    public void increment() {
                        relayMeters.relayRetries().increment();
                    }

                    @Override
                    public Double get() {
                        return relayMeters.relayRetries().count();
                    }
                },
                new RetryBudgetDropCountIncrementor(meterRegistry));
    }
}
