package io.github.pdkovacs.wsgw;

import io.github.pdkovacs.wsgw.logging.CtxLogger;
import io.opentelemetry.instrumentation.micrometer.v1_5.OpenTelemetryMeterRegistry;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;

public class Main {

    static void main(String[] args) {
        CtxLogger logger = CtxLogger.of(Main.class);

        var openTelemetrySdk = AutoConfiguredOpenTelemetrySdk.initialize().getOpenTelemetrySdk();
        var wsgw = new Wsgw(Configuration.fromEnv(), OpenTelemetryMeterRegistry.builder(openTelemetrySdk).build());
        int port;
        try {
            port = wsgw.start();
            try {
                System.out.printf("%s listening on port %s%n", Main.class.getCanonicalName(), (Integer) port);
            } finally {
                wsgw.stop();
                openTelemetrySdk.close();
            }
        } catch (Exception e) {
            logger.error("Top-level application error", e);
            System.exit(1);
        }
    }
}
