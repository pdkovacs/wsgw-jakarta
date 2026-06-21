package io.github.pdkovacs.wsgw.fake.app;

import io.github.pdkovacs.wsgw.ConnectionIdExtractor;
import io.github.pdkovacs.wsgw.Message;
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
        private final ConcurrentMap<String, WsgwEndpoint> connectionEndpointMap;

        public Connect(ConcurrentMap<String, WsgwEndpoint> connectionEndpointMap) {
            this.connectionEndpointMap = connectionEndpointMap;
        }

        protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws IOException, ServletException {
            var path = req.getServletPath();
            logger.debug("MockAppServer: incoming request {} {}, servletPath: {}", req.getMethod(),
                    req.getRequestURI(),
                    path);

            var connectionId = ConnectionIdExtractor.extract(req.getServletPath(), 2);
            connectionEndpointMap.put(connectionId, new WsgwEndpoint(connectionId));
            logger.debug("Endpoint mapped to {}", connectionId);

            res.setStatus(HttpServletResponse.SC_NO_CONTENT);
        }
    }

    public static class Disconnect extends HttpFilter {
        private static final CtxLogger logger = CtxLogger.of(Connect.class);
        private final ConcurrentMap<String, WsgwEndpoint> connectionEndpointMap;

        public Disconnect(ConcurrentMap<String, WsgwEndpoint> connectionEndpointMap) {
            this.connectionEndpointMap = connectionEndpointMap;
        }

        protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws IOException, ServletException {
            var path = req.getServletPath();
            logger.debug("MockAppServer: incoming request {} {}, servletPath: {}", req.getMethod(),
                    req.getRequestURI(),
                    path);

            var connectionId = ConnectionIdExtractor.extract(req.getServletPath(), 2);
            var wsgwEndpoint = connectionEndpointMap.remove(connectionId);
            try {
                wsgwEndpoint.messages.put(Message.EndOfStream.INSTANCE);
                res.setStatus(HttpServletResponse.SC_NO_CONTENT);
                logger.debug("Poison sent to {}", connectionId);
            } catch (InterruptedException e) {
                logger.debug("Interrupted while sending poison sent to {}", connectionId);
            }
        }
    }

    public static class ReceiveMessage extends HttpFilter {
        private static final CtxLogger logger = CtxLogger.of(Connect.class);
        private final ConcurrentMap<String, WsgwEndpoint> connectionEndpointMap;

        public ReceiveMessage(ConcurrentMap<String, WsgwEndpoint> connectionEndpointMap) {
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
            try {
                connectionEndpointMap.get(connectionId).messages.put(new Message.Text(message));
            } catch (InterruptedException e) {
                cmlogger.warn("Interrupted while trying to add message to inbox", e);
            }
            cmlogger.debug("message recorded", message);
        }
    }
}
