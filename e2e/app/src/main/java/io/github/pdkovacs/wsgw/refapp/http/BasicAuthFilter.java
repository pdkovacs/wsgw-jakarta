package io.github.pdkovacs.wsgw.refapp.http;

import io.github.pdkovacs.wsgw.refapp.config.AppConfig;
import io.github.pdkovacs.wsgw.refapp.config.PasswordCredentials;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class BasicAuthFilter implements ContainerRequestFilter {

    private static final Logger log = Logger.getLogger(BasicAuthFilter.class);

    // Public endpoints — mirrors the Node ref's pre-protected /app-info route.
    private static final Set<String> PUBLIC_PATHS = Set.of("/app-info");

    private final AppConfig appConfig;
    private final RequestUser requestUser;

    public BasicAuthFilter(AppConfig appConfig, RequestUser requestUser) {
        this.appConfig = appConfig;
        this.requestUser = requestUser;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        if (PUBLIC_PATHS.contains(path)) {
            return;
        }

        String header = ctx.getHeaderString(HttpHeaders.AUTHORIZATION);
        PasswordCredentials credentials = parseBasic(header);
        if (credentials == null) {
            unauthorized(ctx);
            return;
        }

        boolean match = appConfig.passwordCredentialsList().stream()
                .anyMatch(c -> c.username().equals(credentials.username())
                        && c.password().equals(credentials.password()));

        if (!match) {
            unauthorized(ctx);
            return;
        }

        requestUser.setUserId(credentials.username());
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
                .header(HttpHeaders.WWW_AUTHENTICATE, "Basic")
                .build());
    }
}
