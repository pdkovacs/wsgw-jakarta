package io.github.pdkovacs.wsgw.unit;

import io.github.pdkovacs.wsgw.Configuration;
import io.github.pdkovacs.wsgw.config.Env;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationServerPortTest {

    private static Configuration fromEnv(Map<String, String> extra) {
        var vars = new HashMap<>(Map.of("WSGW_APP_BASE_URL", "http://app:8080"));
        vars.putAll(extra);
        return Configuration.fromEnv(Env.of(vars::get));
    }

    @Test
    void defaultsTo8080() {
        assertThat(new Configuration().getServerPort()).isEqualTo(8080);
        assertThat(fromEnv(Map.of()).getServerPort()).isEqualTo(8080);
    }

    @Test
    void serverPortEnvVarOverrides() {
        assertThat(fromEnv(Map.of("WSGW_SERVER_PORT", "9090")).getServerPort()).isEqualTo(9090);
    }
}
