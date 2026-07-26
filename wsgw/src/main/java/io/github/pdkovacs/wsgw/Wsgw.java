package io.github.pdkovacs.wsgw;

import io.github.pdkovacs.wsgw.appward.Relays;
import io.github.pdkovacs.wsgw.socket.WsConnections;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import io.github.pdkovacs.wsgw.routehandlers.ConnectionRequest;
import io.github.pdkovacs.wsgw.routehandlers.DisconnectRequest;
import io.github.pdkovacs.wsgw.routehandlers.MessageRequest;
import io.github.pdkovacs.wsgw.socket.WsListener;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.http2.Http2Protocol;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.apache.tomcat.websocket.server.WsSci;

import java.util.Set;

public class Wsgw {

    private static final CtxLogger logger = CtxLogger.of(Wsgw.class);

    private final Configuration configuration;
    private final ConnectionIdProvider connectionIdProvider;

    private Tomcat tomcat;

    public Wsgw(Configuration configuration) {
        // Production default: the JVM temp dir is always present and writable,
        // and there is no Maven "target/" to rely on outside the build.
        this(configuration, ConnectionIdProvider.DEFAULT);
    }

    public Wsgw(Configuration configuration, ConnectionIdProvider connectionIdProvider) {
        this.configuration = configuration;
        this.connectionIdProvider = connectionIdProvider;
    }

    public int start() throws Exception {
        tomcat = new Tomcat();
        tomcat.setBaseDir(configuration.getBaseDir().toAbsolutePath().toString());
        tomcat.setPort(0);
        tomcat.getConnector().setProperty("useVirtualThreads", "true"); // ← keeps the VT-blocking model

        // Advertise h2c on the connector so HTTP/2-capable callers (the app->client push leg) can
        // upgrade. This is client-opt-in: HTTP/1.1 callers and the WebSocket (Upgrade: websocket)
        // handshake are unaffected, since they never send the h2c upgrade tokens.
        //-- Create and configure the HTTP/2 protocol object
        Http2Protocol http2Protocol = new Http2Protocol();
        //-- Set maximum allowed active streams per connection
        http2Protocol.setMaxConcurrentStreams(2000);
        //-- Set maximum streams allocated to active request threads
        http2Protocol.setMaxConcurrentStreamExecution(2000);
        //-- Add the configured upgrade protocol to your connector
        tomcat.getConnector().addUpgradeProtocol(http2Protocol);

        Context ctx = tomcat.addContext("", null);

        // A bare addContext() has no servlet, so requests 404 before any filter
        // (including Tomcat's WsFilter) runs. Anchor the chain with a default servlet.
        Tomcat.addServlet(ctx, "default", new org.apache.catalina.servlets.DefaultServlet());
        ctx.addServletMappingDecoded("/", "default");

        WsConnections wsConnections = new WsConnections(this.configuration.getPushToClientWaitTimeout());

        // register the connect filter: it generates the connection id, authenticates
        // the connect against the app, and injects X-WSGW-CONNECTION-ID for the
        // handshake (modifyHandshake) to read. Without it, connectionId is "?".
        addFilters(ctx, wsConnections);

        var appwardRelay = new Relays(configuration.getAppBaseUrl(), configuration.getAppwardDispatcherQueueSize());

        // turn on WS support + register the endpoint before the context finishes
        // starting
        ctx.addServletContainerInitializer(new WsSci() {
            @Override
            public void onStartup(Set<Class<?>> clazzes, ServletContext ctx) throws ServletException {
                ctx.addListener(new WsListener(appwardRelay, wsConnections));
                super.onStartup(clazzes, ctx);
            }
        }, null);

        tomcat.start();
        return tomcat.getConnector().getLocalPort();
    }

    private void addFilters(Context ctx, WsConnections wsConnections) {
        addFilter(ctx, new ConnectionRequest(configuration.getAppBaseUrl(), this.connectionIdProvider), WsgwPaths.CONNECT_FROM_CLIENT);
        addFilter(ctx, new MessageRequest(wsConnections), WsgwPaths.MESSAGE_FROM_APP.concat("/*"));
        addFilter(ctx, new DisconnectRequest(wsConnections), WsgwPaths.DISCONNECT_FROM_APP.concat("/*"));
    }

    public void stop() {
        try {
            logger.debug("Stopping server...");
            tomcat.stop();
            logger.debug("Server stopped...");
        } catch (Exception e) {
            logger.error("Failed to stop server");
            throw new RuntimeException(e);
        }
    }

    static void addFilter(Context ctx, HttpFilter filter, String urlPattern) {
        // The filter name only has to be unique within the context; the URL pattern
        // already is, so use it as the name and avoid a redundant, easily-mismatched arg.
        FilterDef fd = new FilterDef();
        fd.setFilterName(urlPattern);
        fd.setFilter(filter);
        ctx.addFilterDef(fd);
        FilterMap fm = new FilterMap();
        fm.setFilterName(urlPattern);
        fm.addURLPattern(urlPattern);
        ctx.addFilterMap(fm);
    }
}
