package io.github.pdkovacs.wsgw.e2e.client;

import io.github.pdkovacs.wsgw.e2e.common.auth.BasicAuthHttpFilter;
import io.github.pdkovacs.wsgw.e2e.common.auth.BasicAuthenticator;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import io.github.pdkovacs.wsgw.version.VersionServlet;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.http2.Http2Protocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

import static io.github.pdkovacs.wsgw.web.TomcatFilters.addFilter;

public class Client {
    private static final CtxLogger logger = CtxLogger.of(Client.class);
    private static final Logger log = LoggerFactory.getLogger(Client.class);

    private final Config config;

    private Tomcat tomcat;

    public Client(Config config, MeterRegistry meterRegistry) {
        this.config = config;
    }

    public int start() throws LifecycleException {
        tomcat = new Tomcat();
        tomcat.setBaseDir(config.getBaseDir().toAbsolutePath().toString());
        tomcat.setPort(config.getServerPort());
        tomcat.getConnector().setProperty("useVirtualThreads", "true"); // ← keeps the VT-blocking model

        // Advertise h2c on the connector so HTTP/2-capable callers (the PUSH flow's app->gateway hop) can
        // upgrade. This is client-opt-in: HTTP/1.1 callers and the WebSocket (Upgrade: websocket)
        // handshake are unaffected, since they never send the h2c upgrade tokens.
        //-- Create and configure the HTTP/2 protocol object
        Http2Protocol http2Protocol = new Http2Protocol();
        //-- Set maximum allowed active streams per HTTP/2 connection
        http2Protocol.setMaxConcurrentStreams(2000);
        //-- Set maximum streams allocated to active request threads
        http2Protocol.setMaxConcurrentStreamExecution(2000);
        //-- Add the configured upgrade protocol to your connector
        tomcat.getConnector().addUpgradeProtocol(http2Protocol);

        Context ctx = tomcat.addContext("", null);

        var authenticator = new BasicAuthenticator(config.getPasswordCredentials());
        var authnFilter = new BasicAuthHttpFilter(authenticator, Set.of("get-version"));
        addFilter(ctx, authnFilter, "/*");

        // unknown paths get 401 instead of 404, so an unauthenticated caller can't tell which paths exist
        Tomcat.addServlet(ctx, "default", new org.apache.catalina.servlets.DefaultServlet());
        ctx.addServletMappingDecoded("/", "default");

        Tomcat.addServlet(ctx, "get-version", new VersionServlet(Client.class)).addMapping(VersionServlet.PATH);

        tomcat.start();
        return tomcat.getConnector().getLocalPort();
    }

    // Blocks until stop(). Tomcat's connector threads are daemons, so a main() that
    // returns right after start() lets the JVM exit
    public void await() {
        tomcat.getServer().await();
    }

    public void stop() {
        try {
            logger.info("Stopping Tomcat...");
            tomcat.stop();
            logger.info("Tomcat stopped");
            tomcat.destroy();
        } catch (LifecycleException e) {
            throw new RuntimeException(e);
        }
    }
}
