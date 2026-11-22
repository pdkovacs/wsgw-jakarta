package io.github.pdkovacs.wsgw.e2e.driver;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The driver's meters. Every callback takes the run id last; it goes on the meter as the
 * {@value #RUN_ID_TAG} tag.
 */
public record Monitoring (
        BiConsumer<Integer, String> incMsgRecipientToReachCounter,
        Consumer<String> incMsgCreatedCounter,
        Consumer<String> incMsgParseErrCounter,
        Consumer<String> incOutstandingMsgNotFoundCount,
        Consumer<String> incMsgTextMismatchCounter,
        BiConsumer<Duration, String> recordDeliveryDurationMs,
        BiConsumer<Duration, String> recordDeliveryDurationUs
) {
    private static final String RUN_ID_TAG = "runId";

    /**
     * Same as the Go client's unit for all of its meters. An annotation in braces adds no unit suffix
     * to the Prometheus names, so the dashboards' metric names stay as they were.
     */
    private static final String UNIT = "{call}";

    /**
     * Records nothing.
     */
    public static Monitoring noop() {
        return new Monitoring((count, runId) -> {}, runId -> {}, runId -> {}, runId -> {}, runId -> {},
                (duration, runId) -> {}, (duration, runId) -> {});
    }

    /**
     * Meter names and descriptions are the Go client's.
     */
    public static Monitoring micrometer(MeterRegistry registry) {
        var recipientToReach = counter(registry, "ws.e2e.test.client.msg.recipient.counter", "MsgRecipientToReachCounter");
        var created = counter(registry, "ws.e2e.test.client.msg.created", "MsgCreatedCounter");
        var parseErr = counter(registry, "ws.e2e.test.client.msg.parse.error", "MsgParseErrCounter");
        var notFound = counter(registry, "ws.e2e.test.client.outstanding.msg.notfound.error", "MsgNotFoundCounter");
        var textMismatch = counter(registry, "ws.e2e.test.client.outstanding.msg.text.mismatch.error", "dMsgTextMismatchCounter");

        // The Go client's bucket boundaries, less its 0: Micrometer accepts only positive ones
        var deliveryMs = DistributionSummary.builder("ws.e2e.test.delivery.duration.ms")
                .description("Message delivery duration in milliseconds")
                .baseUnit(UNIT)
                .serviceLevelObjectives(1, 2, 5, 10, 25, 50, 100, 250, 500, 1000, 2000, 4000, 8000, 16000)
                .withRegistry(registry);
        var deliveryUs = DistributionSummary.builder("ws.e2e.test.delivery.duration.us")
                .description("Message delivery duration in microseconds")
                .baseUnit(UNIT)
                .withRegistry(registry);

        return new Monitoring(
                (count, runId) -> recipientToReach.withTag(RUN_ID_TAG, runId).increment(count),
                runId -> created.withTag(RUN_ID_TAG, runId).increment(),
                runId -> parseErr.withTag(RUN_ID_TAG, runId).increment(),
                runId -> notFound.withTag(RUN_ID_TAG, runId).increment(),
                runId -> textMismatch.withTag(RUN_ID_TAG, runId).increment(),
                (duration, runId) -> deliveryMs.withTag(RUN_ID_TAG, runId).record(duration.toNanos() / 1_000_000.0),
                (duration, runId) -> deliveryUs.withTag(RUN_ID_TAG, runId).record(duration.toNanos() / 1_000.0));
    }

    private static Meter.MeterProvider<Counter> counter(MeterRegistry registry, String name, String description) {
        return Counter.builder(name).description(description).baseUnit(UNIT).withRegistry(registry);
    }
}
