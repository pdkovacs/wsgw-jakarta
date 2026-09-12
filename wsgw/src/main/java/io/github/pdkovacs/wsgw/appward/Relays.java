package io.github.pdkovacs.wsgw.appward;

import io.github.pdkovacs.wsgw.logging.CtxLogger;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.IntPredicate;

public class Relays {
    private static final CtxLogger logger = CtxLogger.of(Relays.class);

    private final Request appwardRequest;
    private final ConcurrentHashMap<String, Relay> relays = new ConcurrentHashMap<>();
    private final Dispatcher.QueueParams queueParams;

    public Relays(Request appwardRequest, Dispatcher.QueueParams queueParams, MeterRegistry meterRegistry) {
        this.appwardRequest = appwardRequest;
        this.queueParams = queueParams;
        createMeters(meterRegistry);
    }

    public Relay createRelay(Map<String, List<String>> requestHeaders, String connectionId) {
        var relay = new Relay(appwardRequest, requestHeaders, connectionId, queueParams);
        relays.put(connectionId, relay);
        return relay;
    }

    ;

    public Relay get(String connectionId) {   // retire from registry, hand it back
        return relays.get(connectionId);
    }

    public void scanForRemoveDefunctAsync() {
        Thread.ofVirtual().start(() -> {
            for (var relay : relays.entrySet().stream().toList()) {
                if (relay.getValue().isDefunct()) {
                    relays.remove(relay.getKey());
                }
            }
        });
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

    private void createMeters(MeterRegistry meterRegistry) {

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

        Counter relayEnqueueDrops = Counter.builder("wsgw.relay.enqueue.drops")
                .tag("flow", "relay")
                .tag("site", "client_to_gw").register(meterRegistry);
    }
}
