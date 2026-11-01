package io.github.pdkovacs.wsgw.e2e.driver.web;

import io.github.pdkovacs.wsgw.e2e.common.auth.BasicAuthHttpFilter;
import io.github.pdkovacs.wsgw.e2e.common.auth.BasicAuthenticator;
import io.github.pdkovacs.wsgw.e2e.driver.Config;
import io.github.pdkovacs.wsgw.e2e.driver.Monitoring;
import io.github.pdkovacs.wsgw.e2e.driver.service.TestRun;
import io.github.pdkovacs.wsgw.e2e.driver.service.TestRunner;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import io.github.pdkovacs.wsgw.version.VersionServlet;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.http2.Http2Protocol;

import java.net.http.HttpClient;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.pdkovacs.wsgw.web.TomcatFilters.addFilter;

public class Assembler {
    private static final CtxLogger logger = CtxLogger.of(Assembler.class);

    private final ConcurrentHashMap<String, TestRun> testRuns = new ConcurrentHashMap<>();
    private final Config config;
    private final HttpClient httpClient;

    private Tomcat tomcat;

    public Assembler(Config config, MeterRegistry meterRegistry) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder().build();
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

        Tomcat.addServlet(ctx, "get-version", new VersionServlet(Assembler.class)).addMapping(VersionServlet.PATH);
        var testRunner = new TestRunner(
                httpClient,
                config.getWsgwUri(),
                config.getPasswordCredentials(),
                config.getSenderCredentials(),
                Monitoring.noop());
        Tomcat.addServlet(ctx, "run", new RunnerServlet(httpClient, testRunner))
                .addMapping(RunnerServlet.PATH);

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
