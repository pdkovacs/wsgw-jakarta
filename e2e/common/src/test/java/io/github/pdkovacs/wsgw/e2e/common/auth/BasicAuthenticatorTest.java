package io.github.pdkovacs.wsgw.e2e.common.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BasicAuthenticatorTest {

    private final BasicAuthenticator authenticator = new BasicAuthenticator(List.of(
            new PasswordCredentials("test", "se:cret"),
            new PasswordCredentials("other", "pw")));

    private static String basic(String userAndPassword) {
        return "Basic " + Base64.getEncoder().encodeToString(userAndPassword.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void acceptsMatchingCredentials() {
        assertThat(authenticator.authenticate(basic("test:se:cret"))).contains("test");
        assertThat(authenticator.authenticate(basic("other:pw"))).contains("other");
    }

    @Test
    void schemeIsCaseInsensitive() {
        assertThat(authenticator.authenticate(basic("other:pw").replace("Basic", "basic"))).contains("other");
    }

    @Test
    void rejectsWrongPasswordOrUnknownUser() {
        assertThat(authenticator.authenticate(basic("test:nope"))).isEmpty();
        assertThat(authenticator.authenticate(basic("nobody:pw"))).isEmpty();
        assertThat(authenticator.authenticate(basic("other:se:cret"))).isEmpty();
    }

    @Test
    void rejectsMissingOrMalformedHeader() {
        assertThat(authenticator.authenticate(null)).isEmpty();
        assertThat(authenticator.authenticate("Bearer abc")).isEmpty();
        assertThat(authenticator.authenticate("Basic !!!not-base64!!!")).isEmpty();
        assertThat(authenticator.authenticate(basic("no-colon"))).isEmpty();
    }
}
