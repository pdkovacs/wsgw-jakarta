package io.github.pdkovacs.wsgw.e2e.app.logging;

import org.jboss.logging.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the MDC contract of the Quarkus CtxLogger: bound fields are pushed into
 * the MDC only transiently, so they neither leak after an emit nor clobber
 * request-scoped MDC put by {@code RequestContextFilter}.
 *
 * <p>That the fields actually reach the JSON output is covered by the running app
 * (the {@code mdc} object in the JSON console formatter).
 */
class CtxLoggerTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void aBoundFieldDoesNotLeakIntoTheMdcAfterEmit() {
        var log = CtxLogger.of(CtxLoggerTest.class).with("connId", "abc-123");

        log.info("message {}", "body");

        assertThat(MDC.get("connId")).isNull();
    }

    @Test
    void emitRestoresAPreexistingRequestScopedMdcValue() {
        // simulate RequestContextFilter having set request context
        MDC.put("serviceName", "e2e-app");

        var log = CtxLogger.of(CtxLoggerTest.class).with("serviceName", "override");
        log.info("transiently overrides serviceName");

        // after the emit, the request-scoped value is back, unclobbered
        assertThat(MDC.get("serviceName")).isEqualTo("e2e-app");
    }

    @Test
    void withIsImmutableAndNullValuesAreIgnored() {
        var base = CtxLogger.of(CtxLoggerTest.class);

        // null value adds nothing and must not throw (Map.copyOf would reject null)
        assertThat(base.with("connId", null)).isSameAs(base);
    }
}
