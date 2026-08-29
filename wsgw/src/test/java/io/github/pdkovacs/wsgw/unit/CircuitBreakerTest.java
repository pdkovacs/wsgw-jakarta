package io.github.pdkovacs.wsgw.unit;

import io.github.pdkovacs.wsgw.CircuitBreaker;
import org.junit.jupiter.api.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(5)
public class CircuitBreakerTest {

    private static final Duration defaultTestWindow = Duration.ofSeconds(5);
    private static final int defaultTestThreshold = 3;
    private static final Duration defaultTestHoldDown = Duration.ofSeconds(10);

    private MutableClock clock;
    private CircuitBreaker underTest;

    @BeforeEach
    void setup() {
         clock = new MutableClock(Instant.EPOCH);
         underTest = createInstanceToTest(clock);
    }

    @Test
    @DisplayName("remaining() returns null when not shedding")
    void returnsNullRemainingWhenNotShedding() {
        assertThat(underTest.remaining()).isNull();

        clock.advance(Duration.ofSeconds(1));
        assertThat(underTest.remaining()).isNull();

        underTest.incrementBy(1);
        clock.advance(Duration.ofSeconds(1));
        assertThat(underTest.remaining()).isNull();
    }

    @Test
    @DisplayName("increment() returns the full hold-down duration at the exact moment the threshold is crossed")
    void incrementReturnsFullHoldDownAtThresholdCrossing() {
        underTest.incrementBy(defaultTestThreshold + 1);
        assertThat(underTest.remaining()).isEqualTo(defaultTestHoldDown);
    }

    @Test
    @DisplayName("remaining() returns remaining hold-down when shedding")
    void returnsRemainingWhenShedding() {
        underTest.incrementBy(defaultTestThreshold + 1);
        clock.advance(Duration.ofSeconds(1));
        assertThat(underTest.remaining()).isEqualTo(defaultTestHoldDown.minus(Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("starts shedding when threshold has passed")
    void startsSheddingWhenThresholdPassed() {
        underTest.incrementBy(defaultTestThreshold);
        assertThat(underTest.remaining()).isNull();

        underTest.incrementBy(defaultTestThreshold + 1);
        assertThat(underTest.remaining()).isEqualTo(defaultTestHoldDown);
    }

    @Test
    @DisplayName("stops shedding when hold-down elapses")
    void stopsSheddingWhenHoldDownElapses() {
        underTest.incrementBy(defaultTestThreshold + 1);
        assertThat(underTest.remaining()).isNotNull();

        clock.advance(defaultTestHoldDown);
        assertThat(underTest.remaining()).isNull();
    }

    @Test
    @DisplayName("increment() returns null while below threshold (the normal non-tripping path)")
    void incrementReturnsNullWhileBelowThreshold() {
        underTest.incrementBy(defaultTestThreshold - 1);
        assertThat(underTest.remaining()).isNull();
    }

    @Test
    @DisplayName("increment() during hold-down doesn't extend the hold-down")
    void incrementDuringHoldDownDoesntExtendTheHoldDown() {
        underTest.incrementBy(defaultTestThreshold + 1);
        assertThat(underTest.remaining()).isEqualTo(defaultTestHoldDown);
        underTest.incrementBy(2 * defaultTestThreshold);
        clock.advance(defaultTestHoldDown);
        assertThat(underTest.remaining()).isNull();
    }

    @Test
    @DisplayName("window expiry resets counter")
    void windowExpiryResetsCounter() {
        underTest.incrementBy(defaultTestThreshold - 1);
        clock.advance(defaultTestWindow.plus(Duration.ofMillis(1)));
        underTest.incrementBy(2);
        assertThat(underTest.remaining()).isNull();
    }

    @Test
    @DisplayName("hold down expiry resets counter")
    void holdDownExpiryResetsCounter() {
        underTest.incrementBy(defaultTestThreshold + 1);
        assertThat(underTest.remaining()).isNotNull();

        clock.advance(defaultTestHoldDown);
        underTest.incrementBy(defaultTestThreshold - 1);
        assertThat(underTest.remaining()).isNull();
    }

    private CircuitBreaker createInstanceToTest(Clock clock) {
        return new CircuitBreaker(defaultTestWindow, defaultTestThreshold, defaultTestHoldDown, clock);
    };
}

