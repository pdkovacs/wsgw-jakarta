package io.github.pdkovacs.wsgw.e2e.app.routehandlers;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The route handlers' meters. Every callback takes the run id last; it goes on the meter as the
 * {@value #RUN_ID_TAG} tag, or as {@value #UNKNOWN_RUN_ID} when it is {@code null}.
 */
public record Monitoring(
        BiConsumer<Integer, String> incMessageRequestCounter,
        Consumer<String> incConnectRequestCounter,
        Consumer<String> incDisconnectRequestCounter
) {
    private static final String RUN_ID_TAG = "runId";
    private static final String UNKNOWN_RUN_ID = "unknown";

    /**
     * Same as the Go app's unit for its counters. An annotation in braces adds no unit suffix
     * to the Prometheus names, so the dashboards' metric names stay as they were.
     */
    static final String UNIT = "{call}";

    /**
     * Records nothing.
     */
    public static Monitoring noop() {
        return new Monitoring((count, runId) -> {}, runId -> {}, runId -> {});
    }

    /**
     * Meter names and descriptions are the Go app's.
     */
    public static Monitoring micrometer(MeterRegistry registry) {
        var messageRequests = Counter.builder("api.message.request.counter")
                .description("Number of message sending requests via api")
                .baseUnit(UNIT)
                .withRegistry(registry);
        var connectRequests = Counter.builder("ws.connect.request.counter")
                .description("Number of WebSocket connection requests")
                .baseUnit(UNIT)
                .withRegistry(registry);
        var disconnectRequests = Counter.builder("ws.disconnect.request.counter")
                .description("Number of WebSocket disconnection requests")
                .baseUnit(UNIT)
                .withRegistry(registry);

        return new Monitoring(
                (count, runId) -> messageRequests.withTag(RUN_ID_TAG, runIdTag(runId)).increment(count),
                runId -> connectRequests.withTag(RUN_ID_TAG, runIdTag(runId)).increment(),
                runId -> disconnectRequests.withTag(RUN_ID_TAG, runIdTag(runId)).increment());
    }

    // Micrometer rejects a null tag value
    private static String runIdTag(String runId) {
        return Objects.requireNonNullElse(runId, UNKNOWN_RUN_ID);
    }
}
