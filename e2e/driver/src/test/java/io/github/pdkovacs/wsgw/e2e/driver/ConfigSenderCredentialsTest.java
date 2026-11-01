package io.github.pdkovacs.wsgw.e2e.driver;

import io.github.pdkovacs.wsgw.config.Env;
import io.github.pdkovacs.wsgw.e2e.common.auth.PasswordCredentials;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigSenderCredentialsTest {

    @Test
    void senderIsTheFirstOfThePasswordCredentials() {
        var vars = Map.of(
                "E2EDRV_WSGW_HOST", "wsgw",
                "E2EDRV_WSGW_PORT", "8080",
                "E2EDRV_PASSWORD_CREDENTIALS",
                "[{\"username\":\"user1\",\"password\":\"a\"},{\"username\":\"user2\",\"password\":\"b\"}]");

        var config = Config.fromEnv(Env.of(vars::get));

        assertThat(config.getSenderCredentials()).isEqualTo(new PasswordCredentials("user1", "a"));
    }
}
