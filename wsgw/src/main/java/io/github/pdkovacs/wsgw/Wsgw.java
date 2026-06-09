package io.github.pdkovacs.wsgw;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.apache.tomcat.websocket.server.WsSci;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;

public class Wsgw {

    private static final Logger logger = LoggerFactory.getLogger(Wsgw.class);

    public static final String X_WSGW_CONNECTION_ID = "X-WSGW-CONNECTION-ID";

    private final String appBaseUrl;
    // Where Tomcat keeps its scratch/work area. Without this, embedded Tomcat
    // defaults to a "tomcat.<port>" directory under the process working dir,
    // littering the source tree.
    private final Path baseDir;
    private final ConnectionIdProvider connectionIdProvider;

    private Tomcat tomcat;
    // One shared client for the whole gateway: its selector, thread pool and
    // (keep-alive) connection pool are reused across every WS connection, instead
    // of being built up and torn down per request.
    private HttpClient appClient;

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

        appClient = createHttpClient();

        // register the filter on /ws
        FilterDef fd = new FilterDef();
        fd.setFilterName("wsgwAuth");
        fd.setFilter(new WsgwAuthFilter(appBaseUrl, appClient, this.connectionIdProvider));
        ctx.addFilterDef(fd);
        FilterMap fm = new FilterMap();
        fm.setFilterName("wsgwAuth");
        fm.addURLPattern("/*");
        ctx.addFilterMap(fm);

        // turn on WS support + register the endpoint before the context finishes starting
        ctx.addServletContainerInitializer(new WsSci(), null);
        ctx.addApplicationListener(WsgwWsListener.class.getName());

        tomcat.start();
        return tomcat.getConnector().getLocalPort();
    }

    public void stop() {
        try {
            logger.debug("Stopping server...");
            tomcat.stop();
            logger.debug("Server stopped...");
        } catch (Exception e) {
            logger.error("Failed to stop server");
            throw new RuntimeException(e);
        } finally {
            if (appClient != null) {
                appClient.close();
                appClient = null;
            }
        }
    }


    public static HttpClient createHttpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }
}
