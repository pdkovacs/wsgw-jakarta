package io.github.pdkovacs.wsgw;

import java.nio.file.Path;
import java.time.Duration;

public class Configuration {

    final String appBaseUrl;

    // Where Tomcat keeps its scratch/work area. Without this, embedded Tomcat
    // defaults to a "tomcat.<port>" directory under the process working dir,
    // littering the source tree.
    final Path baseDir;

    public Configuration(String appBaseUrl, Path baseDir) {
        this.appBaseUrl = appBaseUrl;
        this.baseDir = baseDir;
    }

    public String getAppBaseUrl() {
        return appBaseUrl;
    }

    public Path getBaseDir() {
        return baseDir != null ? baseDir : Path.of(System.getProperty("java.io.tmpdir"), "wsgw-tomcat");
    }


    public Duration getPushToClientWaitTimeout() {
        return Duration.ofSeconds(10);
    }
}
