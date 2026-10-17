package io.github.pdkovacs.wsgw.config;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

import java.util.List;
import java.util.Map;

/**
 * Fail-fast check of the standard {@code OTEL_*} configuration, for
 * {@code AutoConfiguredOpenTelemetrySdk.builder().addPropertiesCustomizer(OtelConfigCheck::requireExportTarget)}.
 *
 * <p>The SDK reads that configuration itself and starts without complaint when the
 * export target is missing: it falls back to a localhost endpoint and the service
 * name {@code unknown_service:java}. Neither is usable outside a developer machine,
 * so whenever metrics go out over OTLP, both the endpoint and the service name are
 * required. The check reads the SDK's own resolved view, so environment variables
 * and {@code -Dotel.*} system properties count alike.
 */
public final class OtelConfigCheck {

    private OtelConfigCheck() {
    }

    // Overrides nothing: returns an empty map, or throws
    public static Map<String, String> requireExportTarget(ConfigProperties config) {
        if (config.getBoolean("otel.sdk.disabled", false)) {
            return Map.of();
        }
        // The SDK's default when unset is otlp
        if (!config.getList("otel.metrics.exporter", List.of("otlp")).contains("otlp")) {
            return Map.of();
        }
        if (isBlank(config.getString("otel.exporter.otlp.endpoint"))
                && isBlank(config.getString("otel.exporter.otlp.metrics.endpoint"))) {
            throw new IllegalStateException(
                    "OTEL_EXPORTER_OTLP_ENDPOINT (or OTEL_EXPORTER_OTLP_METRICS_ENDPOINT) must be set"
                            + " unless OTEL_SDK_DISABLED=true or OTEL_METRICS_EXPORTER does not include otlp");
        }
        if (isBlank(config.getString("otel.service.name"))
                && isBlank(config.getMap("otel.resource.attributes").get("service.name"))) {
            throw new IllegalStateException(
                    "OTEL_SERVICE_NAME (or service.name in OTEL_RESOURCE_ATTRIBUTES) must be set"
                            + " unless OTEL_SDK_DISABLED=true or OTEL_METRICS_EXPORTER does not include otlp");
        }
        return Map.of();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
