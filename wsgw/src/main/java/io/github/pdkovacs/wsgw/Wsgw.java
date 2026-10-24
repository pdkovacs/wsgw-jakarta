package io.github.pdkovacs.wsgw;

import io.github.pdkovacs.wsgw.appward.Dispatcher;
import io.github.pdkovacs.wsgw.appward.Relays;
import io.github.pdkovacs.wsgw.appward.Request;
import io.github.pdkovacs.wsgw.socket.ConnectionIdProvider;
import io.github.pdkovacs.wsgw.socket.WsConnections;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import io.github.pdkovacs.wsgw.routehandlers.ConnectionRequest;
import io.github.pdkovacs.wsgw.routehandlers.DisconnectRequest;
import io.github.pdkovacs.wsgw.routehandlers.MessageRequest;
import io.github.pdkovacs.wsgw.socket.WsListener;
import io.github.pdkovacs.wsgw.version.VersionServlet;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.http2.Http2Protocol;
import org.apache.tomcat.websocket.server.WsSci;

import java.time.Duration;
import java.util.Set;
import java.util.function.Supplier;

import static io.github.pdkovacs.wsgw.web.TomcatFilters.addFilter;

public class Wsgw {

    private static final CtxLogger logger = CtxLogger.of(Wsgw.class);

    private final Configuration configuration;
    private final ConnectionIdProvider connectionIdProvider;

    private final MeterRegistry meterRegistry;

    private Tomcat tomcat;

    private Relays appwardRelays;

    public Wsgw(Configuration configuration, MeterRegistry meterRegistry) {
        // Production default: the JVM temp dir is always present and writable,
        // and there is no Maven "target/" to rely on outside the build.
        this(configuration, meterRegistry, ConnectionIdProvider.DEFAULT);
    }

    public Wsgw(Configuration configuration, MeterRegistry meterRegistry, ConnectionIdProvider connectionIdProvider) {
        this.configuration = configuration;
        this.meterRegistry = meterRegistry;
        this.connectionIdProvider = connectionIdProvider;
    }

    public int start() throws Exception {
        tomcat = new Tomcat();
        tomcat.setBaseDir(configuration.getBaseDir().toAbsolutePath().toString());
        tomcat.setPort(configuration.getServerPort());
        tomcat.getConnector().setProperty("useVirtualThreads", "true"); // ← keeps the VT-blocking model

        var appwardRequest = new Request(configuration.getAppBaseUrl());

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

        Tomcat.addServlet(ctx, "get-version", new VersionServlet(Wsgw.class)).addMapping(VersionServlet.PATH);

        // A bare addContext() has no servlet, so requests 404 before any filter
        // (including Tomcat's WsFilter) runs. Anchor the chain with a default servlet.
        Tomcat.addServlet(ctx, "default", new org.apache.catalina.servlets.DefaultServlet());
        ctx.addServletMappingDecoded("/", "default");

        var circuitBreaker = new CircuitBreaker(
                configuration.getConnectFailureCountWindow(),
                configuration.getConnectFailurePreemptThreshold(),
                configuration.getConnectPreemptHoldDown());
        WsConnections wsConnections = getWsConnections(circuitBreaker);

        var dispatcherQueueParams = new Dispatcher.QueueParams(
                configuration.getAppwardDispatcherQueueSize(),
                configuration.getRelayEnqueueTimeout());
        var retryParams = new Relays.RetryParams(configuration.getMaxRelayRetries(),
                configuration.getRelayRetryInterval(), configuration.getRelayRetryBudget(),
                configuration.getRelayRetryBudgetWindow());
        appwardRelays = new Relays(appwardRequest, dispatcherQueueParams,
                 configuration.getRelayResponseTimeout(), retryParams, meterRegistry);

        // register the connect filter: it generates the connection id, authenticates
        // the connect against the app, and injects X-WSGW-CONNECTION-ID for the
        // handshake (modifyHandshake) to read. Without it, connectionId is "?".
        addFilters(ctx, wsConnections, configuration.getConnectWaitTimeout(), circuitBreaker);

        // turn on WS support + register the endpoint before the context finishes
        // starting
        ctx.addServletContainerInitializer(new WsSci() {
            @Override
            public void onStartup(Set<Class<?>> clazzes, ServletContext ctx) throws ServletException {
                ctx.addListener(new WsListener(appwardRelays, wsConnections, wsConnections));
                super.onStartup(clazzes, ctx);
            }
        }, null);

        tomcat.start();
        return tomcat.getConnector().getLocalPort();
    }

    private WsConnections getWsConnections(CircuitBreaker circuitBreaker) {
        Supplier<CircuitBreaker> sendLockTimeoutBreakerSupplier = () -> new CircuitBreaker(
                configuration.getSendLockTimeoutCountWindow(),
                configuration.getSendLockTimeoutsPreemptThreshold(),
                configuration.getSendLockTimeoutPreemptHoldDown());
        return new WsConnections(
                this.configuration.getRegistrationWaitTimeout(),
                this.configuration.getSendLockTimeout(),
                circuitBreaker,
                meterRegistry,
                sendLockTimeoutBreakerSupplier);
    }

    private void addFilters(
            Context ctx,
            WsConnections wsConnections,
            Duration connectWaitTimeout,
            CircuitBreaker circuitBreaker) {
        addFilter(ctx,
                new ConnectionRequest(
                        appwardRelays.appwardRequest(),
                        this.connectionIdProvider,
                        configuration.getMaxInFlightConnects(),
                        connectWaitTimeout,
                        circuitBreaker,
                        configuration.getDefaultAdmissionHoldDown(),
                        meterRegistry),
                WsgwPaths.CONNECT_FROM_CLIENT);
        addFilter(ctx, new MessageRequest(wsConnections), WsgwPaths.MESSAGE_FROM_APP.concat("/*"));
        addFilter(ctx, new DisconnectRequest(wsConnections), WsgwPaths.DISCONNECT_FROM_APP.concat("/*"));
    }

    // Blocks until stop(). Tomcat's connector threads are daemons, so a main() that
    // returns right after start() lets the JVM exit
    public void await() {
        tomcat.getServer().await();
    }

    public void stop() {
        try {
            logger.info("Stopping server...");
            tomcat.stop();
            logger.debug("Webcontainer stopped...");
            appwardRelays.stop();
            tomcat.destroy();
            logger.debug("Webcontainer destroyed....");
            logger.debug("appwardRelays closed....");
        } catch (Exception e) {
            logger.error("Failed to stop server");
            throw new RuntimeException(e);
        }
    }
}
