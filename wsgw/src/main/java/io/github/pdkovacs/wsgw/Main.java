package io.github.pdkovacs.wsgw;

import io.github.pdkovacs.wsgw.config.Otel;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import io.opentelemetry.instrumentation.micrometer.v1_5.OpenTelemetryMeterRegistry;
import org.slf4j.bridge.SLF4JBridgeHandler;

public class Main {

    static void main(String[] args) {
        // Tomcat logs through java.util.logging: route it into logback so it comes out as JSON too
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();

        CtxLogger logger = CtxLogger.of(Main.class);

        var otel = Otel.start();
        var wsgw = new Wsgw(Configuration.fromEnv(), OpenTelemetryMeterRegistry.builder(otel.sdk()).build());
        try {
            int port = wsgw.start();
            // The one place both are released: on SIGTERM or System.exit, server first,
            // so that metrics recorded while stopping still get flushed
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    wsgw.stop();
                } finally {
                    otel.close();
                }
            }));
            logger.with("port", port).info("Listening");
            wsgw.await();
        } catch (Exception e) {
            logger.error("Top-level application error", e);
            System.exit(1);
        }
    }
}
