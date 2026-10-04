package io.github.pdkovacs.wsgw;

import java.time.Clock;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.util.function.LongSupplier;

public class MonotonicClock {
    private MonotonicClock() {}

    public static Clock create() {
        LongSupplier nanos = System::nanoTime;
        InstantSource instantSource = () -> Instant.ofEpochSecond(0, nanos.getAsLong());
        return instantSource.withZone(ZoneOffset.UTC);
    }
}
