package io.github.pdkovacs.wsgw.e2e.driver;

import io.github.pdkovacs.wsgw.e2e.common.auth.BasicAuthHttpFilter;
import io.github.pdkovacs.wsgw.e2e.common.auth.BasicAuthenticator;
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

import static io.github.pdkovacs.wsgw.web.TomcatFilters.addFilter;

public class Driver {
    private static final CtxLogger logger = CtxLogger.of(Driver.class);

    private final Config driverConfig;
    private final HttpClient httpClient;
    private final MeterRegistry meterRegistry;

    private Tomcat tomcat;
    private TestRunner testRunner;

    Driver(Config driverConfig, MeterRegistry meterRegistry) {
        this.driverConfig = driverConfig;
        this.httpClient = HttpClient.newBuilder().build();
        this.meterRegistry = meterRegistry;
    }

    int start() throws LifecycleException {
        tomcat = new Tomcat();
        tomcat.setBaseDir(driverConfig.getBaseDir().toAbsolutePath().toString());
        tomcat.setPort(driverConfig.getServerPort());
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

        var authenticator = new BasicAuthenticator(driverConfig.getPasswordCredentials());
        var authnFilter = new BasicAuthHttpFilter(authenticator, Set.of("get-version"));
        addFilter(ctx, authnFilter, "/*");

        // unknown paths get 401 instead of 404, so an unauthenticated caller can't tell which paths exist
        Tomcat.addServlet(ctx, "default", new org.apache.catalina.servlets.DefaultServlet());
        ctx.addServletMappingDecoded("/", "default");

        Tomcat.addServlet(ctx, "get-version", new VersionServlet(Driver.class)).addMapping(VersionServlet.PATH);
        testRunner = new TestRunner(
                httpClient,
                driverConfig.getWsgwServer(),
                driverConfig.getAppServiceUrl(),
                driverConfig.getPasswordCredentials(),
                driverConfig.getSenderCredentials(),
                Monitoring.micrometer(meterRegistry));
        Tomcat.addServlet(ctx, "run", new RunnerServlet(testRunner))
                .addMapping(RunnerServlet.PATH);
        Tomcat.addServlet(ctx, "stop", new StopperServlet(testRunner))
                .addMapping(RunnerServlet.PATH + "/*");

        tomcat.start();
        return tomcat.getConnector().getLocalPort();
    }

    // Blocks until stop(). Tomcat's connector threads are daemons, so a main() that
    // returns right after start() lets the JVM exit
    void await() {
        tomcat.getServer().await();
    }

    void stop() {
        try {
            testRunner.stopTestRun(false);
            logger.info("Stopping Tomcat...");
            tomcat.stop();
            logger.info("Tomcat stopped");
            tomcat.destroy();
        } catch (LifecycleException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            logger.info("Interrupted while waiting for Tomcat to stop");
        }
    }
}
