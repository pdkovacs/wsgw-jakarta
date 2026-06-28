package io.github.pdkovacs.wsgw;

import io.github.pdkovacs.wsgw.logging.CtxLogger;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.http2.Http2Protocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Throwaway discovery spike (delete once it has answered its question): what does the JDK 25
 * {@link java.net.http.HttpClient} actually negotiate against an embedded Tomcat 11 when asked
 * for HTTP/2 — over cleartext (h2c) vs over TLS (ALPN)?
 *
 * <p>Two truths are logged/asserted per run:
 * <ul>
 *   <li>{@code response.version()} — what the <em>client</em> believes it spoke;</li>
 *   <li>the response body = server-side {@code request.getProtocol()} ("HTTP/2.0" or "HTTP/1.1")
 *       — the <em>ground truth</em>, since a silent h2c fallback shows up here.</li>
 * </ul>
 *
 * <p>Each test does a bodyless warm-up GET first (to establish/upgrade the connection) and only
 * then a body-carrying POST — mirroring the real message-from-app path and sidestepping the
 * "upgrade on a POST body" corner.
 */
class Http2NegotiationSpike {

    private static final CtxLogger logger = CtxLogger.of(Http2NegotiationSpike.class);

    /** Echoes the server-observed protocol so the client can see what really happened. */
    private static final class EchoProtocolServlet extends HttpServlet {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            req.getInputStream().readAllBytes(); // drain any POST body
            resp.setContentType("text/plain");
            resp.getWriter().write(req.getProtocol());
        }
    }

    private static Tomcat baseTomcat(Path baseDir) {
        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(baseDir.toAbsolutePath().toString());
        tomcat.setPort(0);
        Context ctx = tomcat.addContext("", null);
        Tomcat.addServlet(ctx, "echo", new EchoProtocolServlet());
        ctx.addServletMappingDecoded("/echo", "echo");
        return tomcat;
    }

    private void probe(HttpClient client, String scheme, int port) throws Exception {
        URI uri = URI.create("%s://localhost:%d/echo".formatted(scheme, port));

        // 1) warm-up GET — lets any h2c upgrade / ALPN settle before a body request
        var warm = client.send(HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        logger.info("[{}] warm-up GET  -> client.version={}, server.getProtocol()={}",
                scheme, warm.version(), warm.body());

        // 2) the real shape: POST with a body
        var post = client.send(HttpRequest.newBuilder(uri)
                        .POST(HttpRequest.BodyPublishers.ofString("hello from app"))
                        .timeout(Duration.ofSeconds(5))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        logger.info("[{}] body  POST  -> client.version={}, server.getProtocol()={}",
                scheme, post.version(), post.body());

        // Ground truth is the server's view; assert on that so a silent h2c->h1 fallback fails loudly.
        assertThat(post.body())
                .as("server-observed protocol for %s (client thinks %s)", scheme, post.version())
                .isEqualTo("HTTP/2.0");
        assertThat(post.version()).isEqualTo(HttpClient.Version.HTTP_2);
    }

    @Test
    @Timeout(30)
    void cleartext_h2c(@TempDir Path tmp) throws Exception {
        Tomcat tomcat = baseTomcat(tmp);
        tomcat.getConnector().addUpgradeProtocol(new Http2Protocol()); // enable h2c on the plaintext connector
        tomcat.start();
        try {
            int port = tomcat.getConnector().getLocalPort();
            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
            probe(client, "http", port);
        } finally {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    @Test
    @Timeout(30)
    void tls_h2_viaAlpn(@TempDir Path tmp) throws Exception {
        Path keystore = generateKeystore(tmp);

        Tomcat tomcat = baseTomcat(tmp);
        Connector connector = tomcat.getConnector();
        connector.setSecure(true);
        connector.setScheme("https");
        connector.setProperty("SSLEnabled", "true");
        SSLHostConfig sslHostConfig = new SSLHostConfig();
        SSLHostConfigCertificate cert =
                new SSLHostConfigCertificate(sslHostConfig, SSLHostConfigCertificate.Type.RSA);
        cert.setCertificateKeystoreFile(keystore.toAbsolutePath().toString());
        cert.setCertificateKeystorePassword("changeit");
        cert.setCertificateKeyAlias("spike");
        sslHostConfig.addCertificate(cert);
        connector.addSslHostConfig(sslHostConfig);
        connector.addUpgradeProtocol(new Http2Protocol()); // h2 negotiated via ALPN over TLS
        tomcat.start();
        try {
            int port = connector.getLocalPort();
            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_2)
                    .sslContext(trustAll())
                    .build();
            probe(client, "https", port);
        } finally {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    /** Generates a self-signed localhost cert via ${java.home}/bin/keytool — no committed binary, no PATH dependency. */
    private static Path generateKeystore(Path dir) throws Exception {
        Path keystore = dir.resolve("spike.p12");
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        Process p = new ProcessBuilder(
                keytool.toString(),
                "-genkeypair", "-alias", "spike",
                "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-validity", "1",
                "-storetype", "PKCS12",
                "-keystore", keystore.toString(),
                "-storepass", "changeit", "-keypass", "changeit")
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes());
        int rc = p.waitFor();
        if (rc != 0) {
            throw new IllegalStateException("keytool failed (rc=" + rc + "):\n" + out);
        }
        return keystore;
    }

    private static SSLContext trustAll() throws Exception {
        var tm = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{tm}, new SecureRandom());
        return ctx;
    }
}
