package io.github.pdkovacs.wsgw;

import io.github.pdkovacs.wsgw.appside.ToApp;
import io.github.pdkovacs.wsgw.clientside.WsConnections;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import io.github.pdkovacs.wsgw.routehandlers.ConnectionRequest;
import io.github.pdkovacs.wsgw.routehandlers.DisconnectRequest;
import io.github.pdkovacs.wsgw.routehandlers.MessageRequest;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.apache.tomcat.websocket.server.WsSci;

import java.nio.file.Path;
import java.util.Set;

public class Wsgw {

    private static final CtxLogger logger = CtxLogger.of(Wsgw.class);

    private final String appBaseUrl;
    // Where Tomcat keeps its scratch/work area. Without this, embedded Tomcat
    // defaults to a "tomcat.<port>" directory under the process working dir,
    // littering the source tree.
    private final Path baseDir;
    private final ConnectionIdProvider connectionIdProvider;

    private Tomcat tomcat;

    public Wsgw(String appBaseUrl) {
        // Production default: the JVM temp dir is always present and writable,
        // and there is no Maven "target/" to rely on outside the build.
        this(appBaseUrl, Path.of(System.getProperty("java.io.tmpdir"), "wsgw-tomcat"), ConnectionIdProvider.DEFAULT);
    }

    public Wsgw(String appBaseUrl, Path baseDir, ConnectionIdProvider connectionIdProvider) {
        logger.debug("appBaseUrl: {}, baseDir: {}", appBaseUrl, baseDir);
        this.appBaseUrl = appBaseUrl;
        this.baseDir = baseDir;
        this.connectionIdProvider = connectionIdProvider;
    }

    public int start() throws Exception {
        tomcat = new Tomcat();
        tomcat.setBaseDir(baseDir.toAbsolutePath().toString());
        tomcat.setPort(0);
        tomcat.getConnector().setProperty("useVirtualThreads", "true"); // ← keeps the VT-blocking model

        Context ctx = tomcat.addContext("", null);

        // A bare addContext() has no servlet, so requests 404 before any filter
        // (including Tomcat's WsFilter) runs. Anchor the chain with a default servlet.
        Tomcat.addServlet(ctx, "default", new org.apache.catalina.servlets.DefaultServlet());
        ctx.addServletMappingDecoded("/", "default");

        WsConnections wsConnections = new WsConnections();

        // register the connect filter: it generates the connection id, authenticates
        // the connect against the app, and injects X-WSGW-CONNECTION-ID for the
        // handshake (modifyHandshake) to read. Without it, connectionId is "?".
        addFilters(ctx, wsConnections);

        var ToApp = new ToApp(appBaseUrl);

        // turn on WS support + register the endpoint before the context finishes
        // starting
        ctx.addServletContainerInitializer(new WsSci() {
            @Override
            public void onStartup(Set<Class<?>> clazzes, ServletContext ctx) throws ServletException {
                ctx.addListener(new WsgwWsListener(ToApp, wsConnections));
                super.onStartup(clazzes, ctx);
            }
        }, null);

        tomcat.start();
        return tomcat.getConnector().getLocalPort();
    }

    private void addFilters(Context ctx, WsConnections wsConnections) {
        addFilter(ctx, new ConnectionRequest(appBaseUrl, this.connectionIdProvider), WsgwPaths.CONNECT_FROM_CLIENT);
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
