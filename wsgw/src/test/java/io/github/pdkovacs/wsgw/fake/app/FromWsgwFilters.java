package io.github.pdkovacs.wsgw.fake.app;

import io.github.pdkovacs.wsgw.ConnectionIdExtractor;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;

import java.io.IOException;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

public class FromWsgwFilters {
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

    public static class Authentication extends HttpFilter {
        private static final CtxLogger logger = CtxLogger.of(Connect.class);

        private final String[] expectedApiKey;

        public Authentication(String[] expectedApiKey) {
            this.expectedApiKey = expectedApiKey;
        }

        protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws IOException, ServletException {
            var connectionId = ConnectionIdExtractor.extract(req.getServletPath(), 1);
            logger.debug("MockAppServer: incoming request with connectionID {}", connectionId);
            String apiKey = req.getHeader(this.expectedApiKey[0]);
            if (apiKey == null) {
                logger.debug("MockAppServer: missing api key {}", expectedApiKey[0]);
                res.setStatus(401);
            } else if (!apiKey.equals(expectedApiKey[1])) {
                logger.debug("MockAppServer: invalid api key value for {}: {}", expectedApiKey[0], expectedApiKey[1]);
                res.setStatus(401);
            } else {
                chain.doFilter(req, res);
            }
            return; // fully handled; don't fall through to DefaultServlet (it would 404 /connect)
        }
    }

    public static class Connect extends HttpFilter {
        private static final CtxLogger logger = CtxLogger.of(Connect.class);
        private final ConcurrentMap<String, WsgwConnectionEndpoint> connectionEndpointMap;

        public Connect(ConcurrentMap<String, WsgwConnectionEndpoint> connectionEndpointMap) {
            this.connectionEndpointMap = connectionEndpointMap;
        }

        protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws IOException, ServletException {
            var path = req.getServletPath();
            logger.debug("MockAppServer: incoming request {} {}, servletPath: {}", req.getMethod(),
                    req.getRequestURI(),
                    path);

            var connectionId = ConnectionIdExtractor.extract(req.getServletPath(), 2);
            connectionEndpointMap.put(connectionId, new WsgwConnectionEndpoint(connectionId));
            logger.debug("Endpoint mapped to {}", connectionId);

            res.setStatus(HttpServletResponse.SC_NO_CONTENT);
        }
    }

    public static class Message extends HttpFilter {
        private static final CtxLogger logger = CtxLogger.of(Connect.class);
        private final ConcurrentMap<String, WsgwConnectionEndpoint> connectionEndpointMap;

        public Message(ConcurrentMap<String, WsgwConnectionEndpoint> connectionEndpointMap) {
            this.connectionEndpointMap = connectionEndpointMap;
        }

        protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws IOException, ServletException {
            var path = req.getServletPath();
            logger.debug("MockAppServer: incoming request {} {}, servletPath: {}", req.getMethod(), req.getRequestURI(),
                    path);

            var connectionId = ConnectionIdExtractor.extract(req.getServletPath(), 2);
            var message = req.getReader().lines().collect(Collectors.joining(System.lineSeparator()));
            var cmlogger = logger.with("connectionId", connectionId).with("message", message);
            cmlogger.debug("message received", message);
            connectionEndpointMap.get(connectionId).messages.add(message);
            cmlogger.debug("message recorded", message);
        }
    }
}
