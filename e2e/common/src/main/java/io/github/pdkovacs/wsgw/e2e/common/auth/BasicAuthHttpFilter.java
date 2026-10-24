package io.github.pdkovacs.wsgw.e2e.common.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * Servlet filter that demands HTTP Basic credentials, except for the servlets named in
 * {@code publicServletNames}. Map it to {@code /*}: a request is public only if the servlet
 * it is routed to is listed, so a servlet that is forgotten there stays protected.
 * On success the username is set as the {@link #USER_ID_ATTRIBUTE} request attribute.
 */
public class BasicAuthHttpFilter extends HttpFilter {

    public static final String USER_ID_ATTRIBUTE = BasicAuthHttpFilter.class.getName() + ".userId";

    private static final String AUTHORIZATION = "Authorization";
    private static final String WWW_AUTHENTICATE = "WWW-Authenticate";

    private final BasicAuthenticator authenticator;
    private final Set<String> publicServletNames;

    public BasicAuthHttpFilter(BasicAuthenticator authenticator, Set<String> publicServletNames) {
        this.authenticator = authenticator;
        this.publicServletNames = Set.copyOf(publicServletNames);
    }

    /** The user {@code BasicAuthHttpFilter} authenticated, empty on public routes. */
    public static Optional<String> userId(HttpServletRequest req) {
        return Optional.ofNullable((String) req.getAttribute(USER_ID_ATTRIBUTE));
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

        req.setAttribute(USER_ID_ATTRIBUTE, userId.get());
        chain.doFilter(req, res);
    }
}
