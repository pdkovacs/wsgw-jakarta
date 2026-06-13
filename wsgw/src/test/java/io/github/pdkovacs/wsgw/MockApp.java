package io.github.pdkovacs.wsgw;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.apache.tomcat.websocket.server.WsSci;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

public class MockApp {

    private static final Logger log = LoggerFactory.getLogger(MockApp.class);

    private Tomcat tomcat;

    public static class MockAppFilter extends HttpFilter {
        private static final Logger log = LoggerFactory.getLogger(MockAppFilter.class);

        private final String[] expectedApiKey;

        public MockAppFilter(String[] expectedApiKey) {
            this.expectedApiKey = expectedApiKey;
        }

        protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws IOException, ServletException {
            var path = req.getServletPath();
            log.debug("MockAppServer: incoming request {} {}, servletPath: {}", req.getMethod(), req.getRequestURI(), path);

            if (path.equals(AppPaths.CONNECT_FROM_WSGW)) {
                var connectionId = req.getHeader(Wsgw.X_WSGW_CONNECTION_ID);
                log.debug("MockAppServer: incoming WSGW connection request with connectionID {}", connectionId);
                String apiKey = req.getHeader(this.expectedApiKey[0]);
                if (apiKey == null) {
                    log.debug("MockAppServer: missing api key {}", expectedApiKey[0]);
                    res.setStatus(401);
                } else if (!apiKey.equals(expectedApiKey[1])) {
                    log.debug("MockAppServer: invalid api key value for {}: {}", expectedApiKey[0], expectedApiKey[1]);
                    res.setStatus(401);
                } else {
                    res.setStatus(HttpServletResponse.SC_NO_CONTENT);
                }
                return;   // fully handled; don't fall through to DefaultServlet (it would 404 /connect)
            } else {
                log.debug("some payload path: {}", path);
            }

            chain.doFilter(req, res);
        }
    }

    int start(Path tomcatBaseDir, String[] expectedApiKey, ConnectionIdProvider connectionIdGeneratorMock) {
        try {
            tomcat = new Tomcat();
            tomcat.setBaseDir(tomcatBaseDir.resolve("appMock").toAbsolutePath().toString());
            tomcat.setPort(0);
            tomcat.getConnector().setProperty("useVirtualThreads", "true");  // ← keeps the VT-blocking model

            Context ctx = tomcat.addContext("", null);

            // A bare context has no servlet, so requests 404 before the filter runs.
            Tomcat.addServlet(ctx, "default", new org.apache.catalina.servlets.DefaultServlet());
            ctx.addServletMappingDecoded("/", "default");

            FilterDef fd = new FilterDef();
            fd.setFilterName("app-filter");
            fd.setFilter(new MockAppFilter(expectedApiKey));
            ctx.addFilterDef(fd);
            FilterMap fm = new FilterMap();
            fm.setFilterName("app-filter");
            fm.addURLPattern("/*");
            ctx.addFilterMap(fm);

            // turn on WS support + register the endpoint before the context finishes starting
            ctx.addServletContainerInitializer(new WsSci(), null);
            ctx.addApplicationListener(WsgwWsListener.class.getName());

            tomcat.start();
            var appPort = tomcat.getConnector().getLocalPort();

            log.debug("appMock started at port: {}", appPort);
            return appPort;
        } catch (Exception e) {
            log.error("Failed to startAppMock", e);
            throw new RuntimeException(e);
        }
    }

    public void stop() throws LifecycleException {
        tomcat.stop();
    }
}
