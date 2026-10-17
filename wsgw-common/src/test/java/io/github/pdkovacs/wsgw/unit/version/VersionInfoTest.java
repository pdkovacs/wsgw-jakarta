package io.github.pdkovacs.wsgw.unit.version;

import io.github.pdkovacs.wsgw.version.VersionInfo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class VersionInfoTest {

    @Test
    void readsAllKeys() throws IOException {
        var props = "application=wsgw\nversion=1.2.3\ncommit=abc123\nbuildTime=2026-10-24T10:00:00Z\n";

        var info = VersionInfo.read(new ByteArrayInputStream(props.getBytes(StandardCharsets.UTF_8)));

        assertThat(info).isEqualTo(new VersionInfo("wsgw", "1.2.3", "abc123", "2026-10-24T10:00:00Z"));
    }

    @Test
    void missingKeysReadAsUnknown() throws IOException {
        var info = VersionInfo.read(new ByteArrayInputStream("version=1\n".getBytes(StandardCharsets.UTF_8)));

        assertThat(info).isEqualTo(new VersionInfo("unknown", "1", "unknown", "unknown"));
    }

    @Test
    void missingResourceReadsAsUnknown() {
        assertThat(VersionInfo.load(VersionInfoTest.class).application()).isEqualTo("unknown");
    }

    @Test
    void jsonEscapesSpecialCharacters() {
        var json = new VersionInfo("a\"b", "c\\d", "e\nf", "g").toJson();

        assertThat(json).isEqualTo(
                "{\"application\":\"a\\\"b\",\"version\":\"c\\\\d\",\"commit\":\"e\\nf\",\"buildTime\":\"g\"}");
    }
}
