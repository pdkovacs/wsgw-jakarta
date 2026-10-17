package io.github.pdkovacs.wsgw.e2e.app.http;

import io.github.pdkovacs.wsgw.e2e.app.config.AppConfig;
import io.github.pdkovacs.wsgw.e2e.app.config.PasswordCredentials;
import io.github.pdkovacs.wsgw.e2e.app.logging.CtxLogger;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class BasicAuthFilter implements ContainerRequestFilter {

    private static final CtxLogger log = CtxLogger.of(BasicAuthFilter.class);

    @Context
    private ResourceInfo resourceInfo;

    private final AppConfig appConfig;
    private final RequestUser requestUser;

    public BasicAuthFilter(AppConfig appConfig, RequestUser requestUser) {
        this.appConfig = appConfig;
        this.requestUser = requestUser;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        var isPublic = isPublic();
        log.debug("isPublic: {}", isPublic);
        if (isPublic) {
            return;
        }

        String header = ctx.getHeaderString(HttpHeaders.AUTHORIZATION);
        PasswordCredentials credentials = parseBasic(header);
        if (credentials == null) {
            unauthorized(ctx);
            return;
        }

        // Password comparison uses MessageDigest.isEqual (data-independent timing).
        // The surrounding loop is NOT constant-time across users (short-circuits via
        // anyMatch, gates password check behind username equality) — acceptable for a
        // reference app whose threat model excludes remote timing analysis.
        boolean match = appConfig.passwordCredentialsList().stream()
                .anyMatch(c -> c.username().equals(credentials.username())
                        && constantTimeEquals(c.password(), credentials.password()));

        if (!match) {
            unauthorized(ctx);
            return;
        }

        requestUser.setUserId(credentials.username());
    }

    private boolean isPublic() {
        Method method = resourceInfo.getResourceMethod();
        Class<?> klass = resourceInfo.getResourceClass();
        return (method != null && method.isAnnotationPresent(Public.class))
                || (klass != null && klass.isAnnotationPresent(Public.class));
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private static PasswordCredentials parseBasic(String header) {
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        try {
            String decoded = new String(
                    Base64.getDecoder().decode(header.substring(6).trim()),
                    StandardCharsets.UTF_8);
            int sep = decoded.indexOf(':');
            if (sep < 0) {
                return null;
            }
            return new PasswordCredentials(decoded.substring(0, sep), decoded.substring(sep + 1));
        } catch (IllegalArgumentException e) {
            log.debug("malformed Authorization header", e);
            return null;
        }
    }

    private static void unauthorized(ContainerRequestContext ctx) {
        ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"wsgw\"")
                .build());
    }
}
