package io.github.pdkovacs.wsgw.e2e.common.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * HTTP Basic authentication against a fixed list of credentials. Knows nothing about
 * JAX-RS or servlets: callers hand it the {@code Authorization} header's value.
 */
public class BasicAuthenticator {

    public static final String CHALLENGE = "Basic realm=\"wsgw\"";

    private final List<PasswordCredentials> accepted;

    public BasicAuthenticator(List<PasswordCredentials> accepted) {
        this.accepted = List.copyOf(accepted);
    }

    /**
     * @param authorizationHeader the {@code Authorization} header's value, may be {@code null}
     * @return the authenticated username, or empty if the header is missing, malformed or
     *         doesn't match any of the accepted credentials
     */
    public Optional<String> authenticate(String authorizationHeader) {
        PasswordCredentials presented = parseBasic(authorizationHeader);
        if (presented == null) {
            return Optional.empty();
        }

        // Password comparison uses MessageDigest.isEqual (data-independent timing).
        // The surrounding loop is NOT constant-time across users (short-circuits via
        // anyMatch, gates password check behind username equality) — acceptable for a
        // reference app whose threat model excludes remote timing analysis.
        boolean match = accepted.stream()
                .anyMatch(c -> c.username().equals(presented.username())
                        && constantTimeEquals(c.password(), presented.password()));
        return match ? Optional.of(presented.username()) : Optional.empty();
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
            return null;
        }
    }
}
