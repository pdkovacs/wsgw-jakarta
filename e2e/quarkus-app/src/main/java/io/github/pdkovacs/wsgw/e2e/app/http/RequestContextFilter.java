package io.github.pdkovacs.wsgw.e2e.app.http;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.MDC;

@Provider
@Priority(Priorities.AUTHENTICATION - 100)
public class RequestContextFilter implements ContainerRequestFilter, ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext ctx) {
        MDC.put("serviceName", "e2e-app");
        MDC.put("requestUrl", ctx.getUriInfo().getRequestUri().toString());
        MDC.put("method", ctx.getMethod());
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        MDC.clear();
    }
}
