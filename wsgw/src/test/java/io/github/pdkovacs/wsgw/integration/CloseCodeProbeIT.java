package io.github.pdkovacs.wsgw.integration;

import io.github.pdkovacs.wsgw.logging.CtxLogger;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.websocket.CloseReason;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.websocket.server.WsSci;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Does a WebSocket close code survive the trip from server to client?
 * <p>
 * Motivated by an observed rewrite — the gateway closed with 1013 (TRY_AGAIN_LATER) and the
 * client reported 1002 (PROTOCOL_ERROR), with the reason phrase intact. The gateway's own logs
 * could not localise it, because both ends there are Tomcat. Reading one server with two
 * independent clients separates the two candidates.
 * <p>
 * <b>Answer:</b> the server is fine. Tomcat's <em>client</em> rewrites 1012, 1013 and 1014 to
 * 1002; the JDK's client receives all of them intact. So wsgw may close with 1013 and real
 * clients will see 1013 — but the Tomcat-based test clients in this suite will not, and must
 * assert 1002 or read the reason phrase instead.
 * The endpoint closes from {@code onOpen}. That was shown to be equivalent to closing from a
 * message handler for 1013, and it keeps the probe to a single round trip.
 * <p>
 * Codes are sent as raw ints via a custom {@link CloseReason.CloseCode} rather than through the
 * {@link CloseReason.CloseCodes} enum, so the probe measures the wire and not the enum's coverage
 * (the enum has no constant for 1014).
 */
public class CloseCodeProbeIT {

    private static final CtxLogger logger = CtxLogger.of(CloseCodeProbeIT.class);

    /**
     * 1004/1005/1006/1015 are omitted: the RFC forbids putting them on the wire, so a rewrite
     * there would be correct behaviour and tell us nothing. 1010 is omitted as client-to-server
     * only. 3000 and 4000 probe the registered-extension and private-use ranges.
     */
    private static final int[] CODES = { 1000, 1001, 1002, 1003, 1007, 1008, 1009, 1011, 1012, 1013, 1014, 3000, 4000 };

    private static final String REASON = "probe";
    private static final long CLOSE_TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private Tomcat tomcat;
    private int port;

    @BeforeEach
    void startServer() throws Exception {
        tomcat = new Tomcat();
        tomcat.setBaseDir(tempDir.toAbsolutePath().toString());
        tomcat.setPort(0);
        tomcat.getConnector();

        Context ctx = tomcat.addContext("", null);
        // Same reason as in Wsgw: without a servlet, requests 404 before Tomcat's WsFilter runs.
        Tomcat.addServlet(ctx, "default", new org.apache.catalina.servlets.DefaultServlet());
        ctx.addServletMappingDecoded("/", "default");

        ctx.addServletContainerInitializer(new WsSci() {
            @Override
            public void onStartup(Set<Class<?>> clazzes, ServletContext servletContext) throws ServletException {
                // super first: it publishes the ServerContainer as a context attribute.
                super.onStartup(clazzes, servletContext);
                var serverContainer = (ServerContainer) servletContext.getAttribute(ServerContainer.class.getName());
                try {
                    serverContainer.addEndpoint(
                            ServerEndpointConfig.Builder.create(ClosingEndpoint.class, "/probe").build());
                } catch (Exception e) {
                    throw new ServletException("failed to register the probe endpoint", e);
                }
            }
        }, null);

        tomcat.start();
        port = tomcat.getConnector().getLocalPort();
    }

    @AfterEach
    void stopServer() throws Exception {
        tomcat.stop();
        tomcat.destroy();
    }

    /**
     * Tomcat's client rewrites 1012, 1013 and 1014 to 1002 (PROTOCOL_ERROR). Those three were
     * added to the IANA registry after RFC 6455, whose own table stops at 1011 — so a validator
     * written against the RFC alone rejects them. This asserts the quirk rather than the ideal,
     * so the suite stays green; if a Tomcat upgrade fixes it, this test fails and says so.
     */
    private static final Map<Integer, Integer> KNOWN_TOMCAT_CLIENT_REWRITES =
            Map.of(1012, 1002, 1013, 1002, 1014, 1002);

    @Test
    @DisplayName("Tomcat's client rewrites the post-RFC6455 close codes to 1002")
    void jakartaClientRewritesOnlyThePostRfcCodes() throws Exception {
        var observed = new LinkedHashMap<Integer, Integer>();
        for (int code : CODES) {
            observed.put(code, closeCodeSeenByJakartaClient(code));
        }
        report("jakarta/tomcat client", observed);
        assertThat(rewrites(observed))
                .as("close codes rewritten between server and Jakarta client")
                .containsExactlyInAnyOrderEntriesOf(KNOWN_TOMCAT_CLIENT_REWRITES);
    }

    @Test
    @DisplayName("close codes survive the trip to a JDK HttpClient WebSocket")
    void jdkClientReceivesTheCodeTheServerSent() throws Exception {
        var observed = new LinkedHashMap<Integer, Integer>();
        for (int code : CODES) {
            observed.put(code, closeCodeSeenByJdkClient(code));
        }
        report("jdk httpclient websocket", observed);
        assertThat(rewrites(observed)).as("close codes rewritten between server and JDK client").isEmpty();
    }

    private int closeCodeSeenByJakartaClient(int requestedCode) throws Exception {
        var closed = new CompletableFuture<CloseReason>();
        var container = ContainerProvider.getWebSocketContainer();
        var endpoint = new Endpoint() {
            @Override
            public void onOpen(Session session, EndpointConfig config) {
                // nothing to do; the server closes immediately
            }

            @Override
            public void onClose(Session session, CloseReason closeReason) {
                closed.complete(closeReason);
            }
        };
        var clientConfig = jakarta.websocket.ClientEndpointConfig.Builder.create().build();
        try (var ignored = container.connectToServer(endpoint, clientConfig, probeUri(requestedCode))) {
            var reason = closed.get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            logger.debug("sent={} received={} phrase='{}'",
                    requestedCode, reason.getCloseCode().getCode(), reason.getReasonPhrase());
            return reason.getCloseCode().getCode();
        }
    }

    private int closeCodeSeenByJdkClient(int requestedCode) throws Exception {
        var closed = new CompletableFuture<Integer>();
        var listener = new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                logger.debug("sent={} received={} phrase='{}'", requestedCode, statusCode, reason);
                closed.complete(statusCode);
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                closed.completeExceptionally(error);
            }
        };
        HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(probeUri(requestedCode), listener)
                .get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return closed.get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private URI probeUri(int code) {
        return URI.create("ws://localhost:%d/probe?code=%d".formatted(port, code));
    }

    /** sent → received, for the entries where the two differ. */
    private static Map<Integer, Integer> rewrites(Map<Integer, Integer> observed) {
        var mismatches = new LinkedHashMap<Integer, Integer>();
        observed.forEach((sent, received) -> {
            if (!sent.equals(received)) {
                mismatches.put(sent, received);
            }
        });
        return mismatches;
    }

    private static void report(String client, Map<Integer, Integer> observed) {
        var table = new StringBuilder("close-code fidelity via ").append(client).append('\n');
        observed.forEach((sent, received) -> table
                .append("  %5d -> %5d %s%n".formatted(sent, received, sent.equals(received) ? "" : "  REWRITTEN")));
        logger.info(table.toString());
    }

    /**
     * Closes on open with the code named by the {@code code} query parameter. Public and with a
     * no-arg constructor because the container instantiates it per connection.
     */
    public static class ClosingEndpoint extends Endpoint {
        @Override
        public void onOpen(Session session, EndpointConfig config) {
            int code = Integer.parseInt(session.getRequestParameterMap().get("code").getFirst());
            try {
                session.close(new CloseReason(() -> code, REASON));
            } catch (IOException e) {
                throw new RuntimeException("failed to close the probe session with code " + code, e);
            }
        }
    }
}
