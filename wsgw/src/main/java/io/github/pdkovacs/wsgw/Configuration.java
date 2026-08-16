package io.github.pdkovacs.wsgw;

import java.nio.file.Path;
import java.time.Duration;

public class Configuration {

    final String appBaseUrl;
    final int appwardDispatcherQueueSize;

    // Where Tomcat keeps its scratch/work area. Without this, embedded Tomcat
    // defaults to a "tomcat.<port>" directory under the process working dir,
    // littering the source tree.
    final Path baseDir;

    public Configuration(String appBaseUrl, Path baseDir, int appwardDispatcherQueueSize) {
        this.appBaseUrl = appBaseUrl;
        this.baseDir = baseDir;
        this.appwardDispatcherQueueSize = appwardDispatcherQueueSize;
    }

    public String getAppBaseUrl() {
        return appBaseUrl;
    }

    public int getAppwardDispatcherQueueSize() {
        return appwardDispatcherQueueSize;
    }

    public Path getBaseDir() {
        return baseDir != null ? baseDir : Path.of(System.getProperty("java.io.tmpdir"), "wsgw-tomcat");
    }

    public static Configuration fromEnv() {
        return new Configuration(
                Env.required("APP_BASE_URL"),
                null,   // baseDir defaults internally
                Env.intVar("APPWARD_DISPATCHER_QUEUE_SIZE", 1024));
    }

    public Duration getPushToClientWaitTimeout() {
        return Duration.ofSeconds(10);
    }

    public Duration getPushWaitForSendMessageDesaturation() {
        return Duration.ofSeconds(10);
    }
}
