package io.github.pdkovacs.wsgw.e2e.common.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.security.Principal;
import java.util.Optional;
import java.util.Set;

/**
 * Servlet filter that demands HTTP Basic credentials, except for the servlets named in
 * {@code publicServletNames}. Map it to {@code /*}: a request is public only if the servlet
 * it is routed to is listed, so a servlet that is forgotten there stays protected.
 * On success the request is passed on wrapped, so the username is what
 * {@link HttpServletRequest#getRemoteUser()} and {@link HttpServletRequest#getUserPrincipal()} return.
 */
public class BasicAuthHttpFilter extends HttpFilter {

    private static final String AUTHORIZATION = "Authorization";
    private static final String WWW_AUTHENTICATE = "WWW-Authenticate";

    private final BasicAuthenticator authenticator;
    private final Set<String> publicServletNames;

    public BasicAuthHttpFilter(BasicAuthenticator authenticator, Set<String> publicServletNames) {
        this.authenticator = authenticator;
        this.publicServletNames = Set.copyOf(publicServletNames);
    }

    @Override
    protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        if (publicServletNames.contains(req.getHttpServletMapping().getServletName())) {
            chain.doFilter(req, res);
            return;
        }

        Optional<String> userId = authenticator.authenticate(req.getHeader(AUTHORIZATION));
        if (userId.isEmpty()) {
            res.setHeader(WWW_AUTHENTICATE, BasicAuthenticator.CHALLENGE);
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        String user = userId.get();
        chain.doFilter(new HttpServletRequestWrapper(req) {
            @Override
            public String getRemoteUser() {
                return user;
            }

            @Override
            public Principal getUserPrincipal() {
                return () -> user;
            }

            @Override
            public String getAuthType() {
                return HttpServletRequest.BASIC_AUTH;
            }
        }, res);
    }
}
