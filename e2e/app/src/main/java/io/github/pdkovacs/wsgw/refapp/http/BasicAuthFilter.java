package io.github.pdkovacs.wsgw.refapp.http;

import io.github.pdkovacs.wsgw.refapp.config.AppConfig;
import io.github.pdkovacs.wsgw.refapp.config.PasswordCredentials;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class BasicAuthFilter implements ContainerRequestFilter {

    private static final Logger log = Logger.getLogger(BasicAuthFilter.class);

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
        log.debugf("isPublic: %b", isPublic);
        if (isPublic) {
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

    private boolean isPublic() {
        Method method = resourceInfo.getResourceMethod();
        Class<?> klass = resourceInfo.getResourceClass();
        return (method != null && method.isAnnotationPresent(Public.class))
                || (klass != null && klass.isAnnotationPresent(Public.class));
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
