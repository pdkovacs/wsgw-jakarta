package io.github.pdkovacs.wsgw;

import io.github.pdkovacs.wsgw.filters.Connect;
import io.github.pdkovacs.wsgw.filters.FromAppMessage;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.apache.tomcat.websocket.server.WsSci;

import java.io.IOException;
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
        tomcat.getConnector().setProperty("useVirtualThreads", "true");  // ← keeps the VT-blocking model

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

        // turn on WS support + register the endpoint before the context finishes starting
        ctx.addServletContainerInitializer(new WsSci() {
            @Override
            public void onStartup(Set<Class<?>> clazzes, ServletContext ctx) throws ServletException {
                ctx.addListener(new WsgwWsListener(createMessageRelay(), wsConnections));
                super.onStartup(clazzes, ctx);
            }
        }, null);


        tomcat.start();
        return tomcat.getConnector().getLocalPort();
    }

    private void addFilters(Context ctx, MessagePusher messagePusher) {
        addFilter(ctx, "connect", new Connect(appBaseUrl, this.connectionIdProvider), WsgwPaths.CONNECT_FROM_CLIENT);
        addFilter(ctx, "message", new FromAppMessage(messagePusher), WsgwPaths.MESSAGE_FROM_APP.concat("/*"));
    }

    private MessageRelay createMessageRelay() {
        return (connectHeaders, connectionId, msg) -> {
            var log = logger.with("connId", connectionId);
            try {
                log.debug("Waiting for futureConnectReqHeaders to resolve...");
                RequestToApp.send(appBaseUrl, connectHeaders, AppPaths.MESSAGE_FROM_WSGW + "/" + connectionId, "POST", msg);
                log.debug("Message sent to app");
            } catch (InterruptedException e) {
                log.warn("Interrupted while waiting for request to connect");
                throw new RuntimeException(e);
            } catch (IOException e) {
                log.warn("IOException while waiting for request to connect", e);
                throw new RuntimeException(e);
            } catch (Exception e) {
                log.error("Exception while waiting for request to connect", e);
                throw new RuntimeException(e);
            }
        };

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

    static void addFilter(Context ctx, String name, HttpFilter filter, String urlPattern) {
        FilterDef fd = new FilterDef();
        fd.setFilterName(name);
        fd.setFilter(filter);
        ctx.addFilterDef(fd);
        FilterMap fm = new FilterMap();
        fm.setFilterName(name);
        fm.addURLPattern(urlPattern);
        ctx.addFilterMap(fm);
    }
}
