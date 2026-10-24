package io.github.pdkovacs.wsgw.e2e.app.http;

import io.github.pdkovacs.wsgw.e2e.app.config.AppConfig;
import io.github.pdkovacs.wsgw.e2e.app.logging.CtxLogger;
import io.github.pdkovacs.wsgw.e2e.common.auth.BasicAuthenticator;
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

@Provider
@Priority(Priorities.AUTHENTICATION)
public class BasicAuthFilter implements ContainerRequestFilter {

    private static final CtxLogger log = CtxLogger.of(BasicAuthFilter.class);

    @Context
    private ResourceInfo resourceInfo;

    private final BasicAuthenticator authenticator;
    private final RequestUser requestUser;

    public BasicAuthFilter(AppConfig appConfig, RequestUser requestUser) {
        this.authenticator = new BasicAuthenticator(appConfig.passwordCredentialsList());
        this.requestUser = requestUser;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        var isPublic = isPublic();
        log.debug("isPublic: {}", isPublic);
        if (isPublic) {
            return;
        }

        authenticator.authenticate(ctx.getHeaderString(HttpHeaders.AUTHORIZATION)).ifPresentOrElse(
                requestUser::setUserId,
                () -> unauthorized(ctx));
    }

    private boolean isPublic() {
        Method method = resourceInfo.getResourceMethod();
        Class<?> klass = resourceInfo.getResourceClass();
        return (method != null && method.isAnnotationPresent(Public.class))
                || (klass != null && klass.isAnnotationPresent(Public.class));
    }

    private static void unauthorized(ContainerRequestContext ctx) {
        ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, BasicAuthenticator.CHALLENGE)
                .build());
    }
}
