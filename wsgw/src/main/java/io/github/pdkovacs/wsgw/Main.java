package io.github.pdkovacs.wsgw;

import io.github.pdkovacs.wsgw.logging.CtxLogger;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

public class Main {

    static void main(String[] args) {
        CtxLogger logger = CtxLogger.of(Main.class);

        var wsgw = new Wsgw(Configuration.fromEnv(), new SimpleMeterRegistry());
        int port;
        try {
            port = wsgw.start();
            try {
                System.out.printf("%s listening on port %s%n", Main.class.getCanonicalName(), (Integer) port);
            } finally {
                wsgw.stop();
            }
        } catch (Exception e) {
            logger.error("Top-level application error", e);
            System.exit(1);
        }
    }
}
