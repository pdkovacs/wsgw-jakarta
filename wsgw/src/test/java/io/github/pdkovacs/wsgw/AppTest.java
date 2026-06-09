package io.github.pdkovacs.wsgw;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.websocket.*;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.apache.tomcat.websocket.server.WsSci;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

public class AppTest {

    private static final Logger log = LoggerFactory.getLogger(AppTest.class);

    @TempDir
    private Path tomcatBaseDir;

    private URI wsgwBaseUrl;

    private final ConnectionIdGeneratorMock connectionIdGeneratorMock =  new ConnectionIdGeneratorMock();

    private final HttpClient httpClient = Wsgw.createHttpClient();
    private final List<WebsocketTestClient> wsTestClients = new ArrayList<>();

    public static class AppFilter extends HttpFilter {
        private static final Logger log = LoggerFactory.getLogger(AppFilter.class);

        private final String[] expectedApiKey;

        public AppFilter(String[] expectedApiKey) {
            this.expectedApiKey = expectedApiKey;
        }

        protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws IOException, ServletException {
            var path = req.getServletPath();
            log.debug("MockAppServer: incoming request {} {}, servletPath: {}", req.getMethod(), req.getRequestURI(), path);

            if (path.equals("/connect")) {
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

    private void startMockApp(String[] expectedApiKey) {
        try {
            Tomcat appMock = new Tomcat();
            appMock.setBaseDir(tomcatBaseDir.resolve("appMock").toAbsolutePath().toString());
            appMock.setPort(0);
            appMock.getConnector().setProperty("useVirtualThreads", "true");  // ← keeps the VT-blocking model

            Context ctx = appMock.addContext("", null);

            // A bare context has no servlet, so requests 404 before the filter runs.
            Tomcat.addServlet(ctx, "default", new org.apache.catalina.servlets.DefaultServlet());
            ctx.addServletMappingDecoded("/", "default");

            FilterDef fd = new FilterDef();
            fd.setFilterName("app-filter");
            fd.setFilter(new AppFilter(expectedApiKey));
            ctx.addFilterDef(fd);
            FilterMap fm = new FilterMap();
            fm.setFilterName("app-filter");
            fm.addURLPattern("/*");
            ctx.addFilterMap(fm);

            // turn on WS support + register the endpoint before the context finishes starting
            ctx.addServletContainerInitializer(new WsSci(), null);
            ctx.addApplicationListener(WsgwWsListener.class.getName());

            appMock.start();
            var appPort = appMock.getConnector().getLocalPort();

            log.debug("appMock started at port: {}", appPort);
            String appMockUrl = "http://localhost:%d".formatted(appPort);

            Wsgw wsgw = new Wsgw(appMockUrl, tomcatBaseDir.resolve("wsgw"), this.connectionIdGeneratorMock);
            wsgwBaseUrl = URI.create("ws://localhost:%d".formatted(wsgw.start()));
        } catch (Exception e) {
            log.error("Failed to startAppMock", e);
            throw new RuntimeException(e);
        }
    }

    @Test
    void canConnectWithValidHeaders() throws Exception {
        String[] apiKey = new String[] { "XKEY", "asdfqwe" };
        startMockApp(apiKey);

        Function<String, Consumer<String>> messageHandler = clientId -> message -> log.debug("[{}] message received: {}", clientId, message);

        URI wsgwWebscoketServerURI = URI.create(wsgwBaseUrl.toString().concat("/ws"));
        String connId1 = this.connectionIdGeneratorMock.roll();
        try (var wsTestClient1 = createConnectWebsocketClient(wsgwWebscoketServerURI, apiKey, messageHandler.apply("client1"))) {
            assertThat(wsTestClient1.connectionId()).isEqualTo(connId1);
            String connId2 = this.connectionIdGeneratorMock.roll();
            try (var wsTestClient2 = createConnectWebsocketClient(wsgwWebscoketServerURI, apiKey, messageHandler.apply("client2"))) {
                assertThat(wsTestClient2.connectionId()).isEqualTo(connId2);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(String.format("http://%s:%d", wsgwBaseUrl.getHost(), wsgwBaseUrl.getPort()).concat("/some-unrelated-rest-endpoint")))
                        .build();
                HttpResponse<String> response;
                try {
                    response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                } catch (Exception e) {
                    log.error("Failed to connect to app ({})", wsgwBaseUrl, e);
                    throw new RuntimeException(e);
                }
                assertThat(response.statusCode()).as("404 from unmapped rest endpoint").isEqualTo(HttpServletResponse.SC_NOT_FOUND);

                try (Session session1 = wsTestClient1.websocketClientSession()) {
                    try (Session session2 = wsTestClient2.websocketClientSession()) {
                        session2.getBasicRemote().sendText("Hello from 2");
                        session1.getBasicRemote().sendText("Hello from 1");
                    }
                }
            }
        }
    }

    private WebsocketTestClient createConnectWebsocketClient(
            URI wsgwWebscoketServerURI,
            String[] apiKey,
            Consumer<String> onText
    ) throws Exception {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();   // ← Tomcat's client impl
        TestClientEndpoint testClientEndpoint = new TestClientEndpoint();
        // To exercise the auth path in tests, attach handshake headers with a client Configurator:
        var futureConnectionId = new CompletableFuture<String>();
        var cfg = ClientEndpointConfig.Builder.create()
                .configurator(new ClientEndpointConfig.Configurator() {
                    @Override
                    public void beforeRequest(Map<String, List<String>> headers) {
                        headers.put(apiKey[0], List.of(apiKey[1]));   // app checks header[name]==value
                    }

                    @Override
                    public void afterResponse(HandshakeResponse hr) {
                        var connectionId = hr.getHeaders().get("X-WSGW-CONNECTION-ID").getFirst();
                        futureConnectionId.complete(connectionId);
                    }
                }).build();
        Session session = container.connectToServer(testClientEndpoint, cfg, wsgwWebscoketServerURI);
        // connectToServer returns only after onOpen has run, so the session is ready here — no latch needed.
        var wsTestClient = new WebsocketTestClient(testClientEndpoint, session, futureConnectionId.get());
        wsTestClients.add(wsTestClient);
        return wsTestClient;
    }
}

record WebsocketTestClient(TestClientEndpoint testClientEndpoint, Session websocketClientSession, String connectionId) implements AutoCloseable {
    public void close() throws Exception {
        // `close` defaults to new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "no reason")
        websocketClientSession.close();
    }
}
