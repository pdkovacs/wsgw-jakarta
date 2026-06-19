package io.github.pdkovacs.wsgw.fake.app;

import io.github.pdkovacs.wsgw.AppPaths;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class FakeApp {

    private static final Logger log = LoggerFactory.getLogger(FakeApp.class);

    private Tomcat tomcat;

    private final ConcurrentMap<String, WsgwConnectionEndpoint> connectionEndpointMap = new ConcurrentHashMap<>();

    public int start(Path tomcatBaseDir, String[] expectedApiKey) {
        try {
            tomcat = new Tomcat();
            tomcat.setBaseDir(tomcatBaseDir.resolve("appMock").toAbsolutePath().toString());
            tomcat.setPort(0);
            tomcat.getConnector().setProperty("useVirtualThreads", "true");  // ← keeps the VT-blocking model

            Context ctx = tomcat.addContext("", null);

            // A bare context has no servlet, so requests 404 before the filter runs.
            Tomcat.addServlet(ctx, "default", new org.apache.catalina.servlets.DefaultServlet());
            ctx.addServletMappingDecoded("/", "default");

            FromWsgwFilters.addFilter(ctx, "authn", new FromWsgwFilters.Authentication(expectedApiKey), "/*");
            FromWsgwFilters.addFilter(ctx, "connect", new FromWsgwFilters.Connect(connectionEndpointMap), AppPaths.CONNECT_FROM_WSGW + "/*");
            FromWsgwFilters.addFilter(ctx, "message", new FromWsgwFilters.Message(connectionEndpointMap), AppPaths.MESSAGE_FROM_WSGW + "/*");

            tomcat.start();
            var appPort = tomcat.getConnector().getLocalPort();

            log.debug("appMock started at port: {}", appPort);
            return appPort;
        } catch (Exception e) {
            log.error("Failed to startAppMock", e);
            throw new RuntimeException(e);
        }
    }

    public WsgwConnectionEndpoint getConnection(String id) {
        return this.connectionEndpointMap.get(id);
    }

    public void stop() throws LifecycleException {
        tomcat.stop();
    }
}
