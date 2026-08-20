package io.github.pdkovacs.wsgw.integration.app.fake;

import io.github.pdkovacs.wsgw.AppPaths;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class FakeApp {

    private static final Logger log = LoggerFactory.getLogger(FakeApp.class);

    private Tomcat tomcat;

    private final ConcurrentMap<String, WsgwEndpoint> connectionEndpointMap = new ConcurrentHashMap<>();

    public int start(FakeAppConfig fakeAppConfig) {
        try {
            tomcat = new Tomcat();
            tomcat.setBaseDir(fakeAppConfig.getTomcatBaseDir().resolve("appMock").toAbsolutePath().toString());
            tomcat.setPort(0);
            tomcat.getConnector().setProperty("useVirtualThreads", "true"); // ← keeps the VT-blocking model

            Context ctx = tomcat.addContext("", null);

            // A bare context has no servlet, so requests 404 before the filter runs.
            Tomcat.addServlet(ctx, "default", new org.apache.catalina.servlets.DefaultServlet());
            ctx.addServletMappingDecoded("/", "default");

            FromWsgwFilters.addFilter(ctx, new FromWsgwFilters.Authentication(fakeAppConfig.getApiKey()), "/*");
            FromWsgwFilters.addFilter(ctx, new FromWsgwFilters.Connect(connectionEndpointMap, fakeAppConfig),
                    AppPaths.CONNECT_FROM_WSGW + "/*");
            FromWsgwFilters.addFilter(ctx, new FromWsgwFilters.Disconnect(connectionEndpointMap),
                    AppPaths.DISCONNECTED_FROM_WSGW + "/*");
            FromWsgwFilters.addFilter(ctx, new FromWsgwFilters.ReceiveMessage(connectionEndpointMap),
                    AppPaths.MESSAGE_FROM_WSGW + "/*");

            tomcat.start();
            var appPort = tomcat.getConnector().getLocalPort();

            log.debug("appMock started at port: {}", appPort);
            return appPort;
        } catch (Exception e) {
            log.error("Failed to startAppMock", e);
            throw new RuntimeException(e);
        }
    }

    public WsgwEndpoint getConnection(String id) {
        var endpoint = this.connectionEndpointMap.get(id);
        if (endpoint == null) {
            throw new NoSuchElementException("No endpoint for connection with id " + id);
        }
        return endpoint;
    }

    public void stop() throws LifecycleException {
        tomcat.stop();
        tomcat.destroy();
    }
}
