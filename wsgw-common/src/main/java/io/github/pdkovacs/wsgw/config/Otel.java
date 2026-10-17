package io.github.pdkovacs.wsgw.config;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.instrumentation.runtimetelemetry.RuntimeTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.resources.Resource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

/**
 * The OpenTelemetry setup shared by the gateway and the e2e harness: metrics over
 * OTLP, configured through the standard {@code OTEL_*} environment variables
 * (or {@code -Dotel.*} system properties) and checked by {@link OtelConfigCheck}.
 *
 * <p>On top of the SDK's own defaults:
 * <ul>
 *   <li>OTLP over {@code http/protobuf}, exported every 5 seconds;</li>
 *   <li>no trace or log export;</li>
 *   <li>{@code service.instance.id} falls back to the hostname, so instances
 *       sharing a {@code service.name} stay apart;</li>
 *   <li>JVM runtime metrics.</li>
 * </ul>
 * The first two are only defaults: any {@code OTEL_*} setting overrides them.
 */
public final class Otel implements AutoCloseable {

    private static final AttributeKey<String> SERVICE_INSTANCE_ID = AttributeKey.stringKey("service.instance.id");

    // Lowest precedence: environment variables and system properties override these
    private static final Map<String, String> DEFAULTS = Map.of(
            "otel.exporter.otlp.protocol", "http/protobuf",
            "otel.metric.export.interval", "5000",
            "otel.traces.exporter", "none",
            "otel.logs.exporter", "none");

    private final OpenTelemetrySdk sdk;
    private final RuntimeTelemetry runtimeTelemetry;

    private Otel(OpenTelemetrySdk sdk, RuntimeTelemetry runtimeTelemetry) {
        this.sdk = sdk;
        this.runtimeTelemetry = runtimeTelemetry;
    }

    // Also registers the SDK as GlobalOpenTelemetry, which can be done once per JVM
    public static Otel start() {
        var sdk = AutoConfiguredOpenTelemetrySdk.builder()
                .addPropertiesSupplier(() -> DEFAULTS)
                .addPropertiesCustomizer(OtelConfigCheck::requireExportTarget)
                .addResourceCustomizer(Otel::withInstanceId)
                .disableShutdownHook()
                .setResultAsGlobal()
                .build()
                .getOpenTelemetrySdk();
        return new Otel(sdk, RuntimeTelemetry.create(sdk));
    }

    public OpenTelemetrySdk sdk() {
        return sdk;
    }

    // Flushes pending metrics. The caller owns this: the SDK's own shutdown hook is
    // disabled, as it would race the caller's own hook and could shut down before the server
    @Override
    public void close() {
        runtimeTelemetry.close();
        sdk.close();
    }

    private static Resource withInstanceId(Resource resource, ConfigProperties config) {
        if (resource.getAttribute(SERVICE_INSTANCE_ID) != null) {
            return resource;
        }
        return resource.merge(Resource.create(Attributes.of(SERVICE_INSTANCE_ID, hostname())));
    }

    // HOSTNAME first: in a container it is the pod or container name, and reading it needs no DNS lookup
    private static String hostname() {
        var fromEnv = System.getenv("HOSTNAME");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            throw new IllegalStateException(
                    "Cannot determine the hostname for service.instance.id;"
                            + " set service.instance.id in OTEL_RESOURCE_ATTRIBUTES", e);
        }
    }
}
