package io.github.pdkovacs.wsgw.e2e.driver;

import java.time.Duration;
import java.util.function.Consumer;

public record Monitoring (
        Consumer<Integer> incMsgRecipientToReachCounter,
        Runnable incMsgCreatedCounter,
        Runnable incMsgParseErrCounter,
        Runnable incOutstandingMsgNotFoundCoun,
        Runnable incdMsgTextMismatchCounter,
        Consumer<Duration> recordDeliveryDurationMs,
        Consumer<Duration> recordDeliveryDurationUs
) {
    /**
     * Records nothing; stands in until the driver's meters get created.
     */
    public static Monitoring noop() {
        return new Monitoring(count -> {}, () -> {}, () -> {}, () -> {}, () -> {}, duration -> {}, duration -> {});
    }
}
