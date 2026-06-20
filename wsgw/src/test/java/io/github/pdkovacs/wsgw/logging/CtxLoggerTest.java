package io.github.pdkovacs.wsgw.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CtxLoggerTest {

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger backing;

    @BeforeEach
    void attachAppender() {
        backing = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(CtxLoggerTest.class);
        backing.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();
        backing.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        backing.detachAppender(appender);
    }

    @Test
    void boundFieldsAreEmittedAsKeyValuePairs() {
        var log = CtxLogger.of(CtxLoggerTest.class)
                .with("connId", "abc-123")
                .with("tenant", "acme");

        log.debug("Message sent to app: {}", "hello");

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);

        // the {} placeholder is still substituted as in plain SLF4J
        assertThat(event.getFormattedMessage()).isEqualTo("Message sent to app: hello");

        // bound context rides along as structured key/value pairs, not message text
        Map<String, Object> kvp = event.getKeyValuePairs().stream()
                .collect(java.util.stream.Collectors.toMap(p -> p.key, p -> p.value));
        assertThat(kvp).containsEntry("connId", "abc-123").containsEntry("tenant", "acme");
    }

    @Test
    void withReturnsADerivedLoggerLeavingTheParentUnchanged() {
        var base = CtxLogger.of(CtxLoggerTest.class);
        var child = base.with("connId", "abc-123");

        base.info("no context here");
        child.info("has context");

        assertThat(keysOf(appender.list.get(0))).isEmpty();
        assertThat(keysOf(appender.list.get(1))).containsExactly("connId");
    }

    private static java.util.List<String> keysOf(ILoggingEvent event) {
        return event.getKeyValuePairs() == null
                ? java.util.List.of()
                : event.getKeyValuePairs().stream().map(p -> p.key).toList();
    }
}
