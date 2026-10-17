package io.github.pdkovacs.wsgw.unit.config;

import io.github.pdkovacs.wsgw.config.Env;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvTest {

    @Test
    void readsFromTheInjectedLookup() {
        var env = Env.of(Map.of(
                "PORT", " 8081 ",
                "H2", "TRUE",
                "TIMEOUT", "PT0.5S",
                "NAME", "wsgw")::get);

        assertThat(env.requiredInt("PORT")).isEqualTo(8081);
        assertThat(env.booleanVar("H2", false)).isTrue();
        assertThat(env.durationVar("TIMEOUT", Duration.ZERO)).isEqualTo(Duration.ofMillis(500));
        assertThat(env.required("NAME")).isEqualTo("wsgw");
    }

    @Test
    void unsetOrBlankFallsBackToTheDefault() {
        var env = Env.of(Map.of("BLANK", "  ")::get);

        assertThat(env.intVar("UNSET", 7)).isEqualTo(7);
        assertThat(env.intVar("BLANK", 7)).isEqualTo(7);
        assertThat(env.stringVar("BLANK", null)).isNull();
    }

    @Test
    void requiredFailsWhenUnsetOrBlank() {
        var env = Env.of(Map.of("BLANK", "")::get);

        assertThatThrownBy(() -> env.required("UNSET"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UNSET");
        assertThatThrownBy(() -> env.requiredInt("BLANK"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BLANK");
    }

    @Test
    void malformedValuesFailInsteadOfFallingBack() {
        var env = Env.of(Map.of("N", "x", "B", "yes", "D", "10s")::get);

        assertThatThrownBy(() -> env.intVar("N", 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> env.booleanVar("B", false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> env.durationVar("D", Duration.ZERO)).isInstanceOf(IllegalStateException.class);
    }
}
