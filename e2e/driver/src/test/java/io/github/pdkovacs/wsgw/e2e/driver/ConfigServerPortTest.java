package io.github.pdkovacs.wsgw.e2e.driver;

import io.github.pdkovacs.wsgw.config.Env;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigServerPortTest {

    private static Config fromEnv(Map<String, String> extra) {
        var vars = new HashMap<>(Map.of(
                "E2EDRV_WSGW_HOST", "wsgw",
                "E2EDRV_WSGW_PORT", "8080",
                "E2EDRV_PASSWORD_CREDENTIALS", "[{\"username\":\"test\",\"password\":\"test\"}]"));
        vars.putAll(extra);
        return Config.fromEnv(Env.of(vars::get));
    }

    @Test
    void defaultsTo8080() {
        assertThat(new Config().getServerPort()).isEqualTo(8080);
        assertThat(fromEnv(Map.of()).getServerPort()).isEqualTo(8080);
    }

    @Test
    void serverPortEnvVarOverrides() {
        assertThat(fromEnv(Map.of("E2EDRV_SERVER_PORT", "9090")).getServerPort()).isEqualTo(9090);
    }
}
