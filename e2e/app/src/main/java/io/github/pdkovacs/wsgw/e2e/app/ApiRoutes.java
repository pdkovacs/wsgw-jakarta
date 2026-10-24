package io.github.pdkovacs.wsgw.e2e.app;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;

import java.util.Set;

public class ApiRoutes {
    private static final Set<String> publicServlets = Set.of("version");

    private ApiRoutes() {
    }

    public static void bindApiRoutes(Config config, Context ctx) throws LifecycleException {
    }

}
