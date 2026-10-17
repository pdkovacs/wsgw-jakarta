package io.github.pdkovacs.wsgw.unit.config;

import io.github.pdkovacs.wsgw.config.OtelConfigCheck;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtelConfigCheckTest {

    // Empty map = the check passed (it throws otherwise) and overrides no SDK property;
    // assertThat(check(...)).isEmpty() asserts both
    private static Map<String, String> check(Map<String, String> properties) {
        return OtelConfigCheck.requireExportTarget(DefaultConfigProperties.createFromMap(properties));
    }

    @Test
    void passesWithEndpointAndServiceName() {
        assertThat(check(Map.of(
                "otel.exporter.otlp.endpoint", "http://collector:4318",
                "otel.service.name", "e2e-client"))).isEmpty();
    }

    @Test
    void acceptsTheSignalSpecificEndpointAndTheResourceAttributeServiceName() {
        assertThat(check(Map.of(
                "otel.exporter.otlp.metrics.endpoint", "http://collector:4318/v1/metrics",
                "otel.resource.attributes", "deployment.environment=test,service.name=e2e-client"))).isEmpty();
    }

    @Test
    void failsWithoutEndpoint() {
        assertThatThrownBy(() -> check(Map.of("otel.service.name", "e2e-client")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OTEL_EXPORTER_OTLP_ENDPOINT");
    }

    @Test
    void failsWithoutServiceName() {
        assertThatThrownBy(() -> check(Map.of("otel.exporter.otlp.endpoint", "http://collector:4318")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OTEL_SERVICE_NAME");
    }

    @Test
    void skipsWhenOtelIsDeliberatelyOff() {
        assertThat(check(Map.of("otel.sdk.disabled", "true"))).isEmpty();
        assertThat(check(Map.of("otel.metrics.exporter", "none"))).isEmpty();
        assertThat(check(Map.of("otel.metrics.exporter", "logging"))).isEmpty();
    }
}
