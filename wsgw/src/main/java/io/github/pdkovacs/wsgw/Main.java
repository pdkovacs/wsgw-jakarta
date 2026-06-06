package io.github.pdkovacs.wsgw;

    import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

    static void main(String[] args) {
        Logger logger = LoggerFactory.getLogger("click.bitkit.wsgw.jetty");

        System.out.println("Hello World!");
        var wsgw = new Wsgw(System.getenv("APP_BASE_URL"));
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
