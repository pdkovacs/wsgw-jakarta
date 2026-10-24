package io.github.pdkovacs.wsgw.e2e.common.auth;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BasicAuthHttpFilterIT {

    private static final String VALID_AUTHORIZATION = "Basic " + Base64.getEncoder()
            .encodeToString("test:test".getBytes(StandardCharsets.UTF_8));
    private static final String INVALID_AUTHORIZATION = "Basic " + Base64.getEncoder()
            .encodeToString("test:nope".getBytes(StandardCharsets.UTF_8));

    @TempDir
    Path baseDir;

    private Tomcat tomcat;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void startTomcat() throws Exception {
        tomcat = new Tomcat();
        tomcat.setBaseDir(baseDir.toString());
        tomcat.setPort(0);
        tomcat.getConnector();
        Context ctx = tomcat.addContext("", baseDir.toString());

        addServlet(ctx, "open", "/open");
        addServlet(ctx, "closed", "/closed");

        var filterDef = new FilterDef();
        filterDef.setFilterName("auth");
        filterDef.setFilter(new BasicAuthHttpFilter(
                new BasicAuthenticator(List.of(new PasswordCredentials("test", "test"))),
                Set.of("open")));
        ctx.addFilterDef(filterDef);
        var filterMap = new FilterMap();
        filterMap.setFilterName("auth");
        filterMap.addURLPattern("/*");
        ctx.addFilterMap(filterMap);

        tomcat.start();
    }

    @AfterEach
    void stopTomcat() throws Exception {
        tomcat.stop();
        tomcat.destroy();
    }

    private static void addServlet(Context ctx, String name, String pattern) {
        Wrapper w = Tomcat.addServlet(ctx, name, new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
                res.getWriter().print(BasicAuthHttpFilter.userId(req).orElse("anonymous"));
            }
        });
        w.addMapping(pattern);
    }

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        var builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + tomcat.getConnector().getLocalPort() + path));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void protectedServletWithoutCredentialsIsChallenged() throws Exception {
        var res = get("/closed", null);
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.headers().firstValue("WWW-Authenticate")).contains(BasicAuthenticator.CHALLENGE);
    }

    @Test
    void protectedServletWithWrongCredentialsIsChallenged() throws Exception {
        assertThat(get("/closed", INVALID_AUTHORIZATION).statusCode()).isEqualTo(401);
    }

    @Test
    void protectedServletSeesAuthenticatedUser() throws Exception {
        var res = get("/closed", VALID_AUTHORIZATION);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).isEqualTo("test");
    }

    @Test
    void publicServletNeedsNoCredentials() throws Exception {
        var res = get("/open", null);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).isEqualTo("anonymous");
    }
}
