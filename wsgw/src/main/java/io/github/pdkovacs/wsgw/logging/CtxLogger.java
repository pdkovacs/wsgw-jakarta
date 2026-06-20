package io.github.pdkovacs.wsgw.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An immutable SLF4J wrapper that carries bound context fields and emits them as
 * structured key/value pairs on every log line — the Go {@code slog.With} /
 * Node {@code pino.child} style.
 *
 * <p>{@link #with} returns a <em>new</em> instance, so context is held on the
 * object rather than in a {@link org.slf4j.MDC} ThreadLocal. That makes a bound
 * logger safe to hand across (virtual) threads — the fields travel with it.
 *
 * <p>Fields land as real JSON properties only when the backend encoder
 * serializes key/value pairs (e.g. logstash-logback-encoder's LogstashEncoder).
 */
public final class CtxLogger {

    private final Logger delegate;
    private final Map<String, Object> fields;   // immutable, insertion-ordered

    private CtxLogger(Logger delegate, Map<String, Object> fields) {
        this.delegate = delegate;
        this.fields = fields;
    }

    public static CtxLogger of(Class<?> clazz) {
        return new CtxLogger(LoggerFactory.getLogger(clazz), Map.of());
    }

    public static CtxLogger of(String name) {
        return new CtxLogger(LoggerFactory.getLogger(name), Map.of());
    }

    /**
     * Returns a derived logger carrying this logger's context plus {@code key=value}.
     * A null value adds nothing — a missing field is simply absent, not logged as "null".
     */
    public CtxLogger with(String key, Object value) {
        if (value == null) {
            return this;
        }
        var next = new LinkedHashMap<String, Object>(fields);
        next.put(key, value);
        return new CtxLogger(delegate, Map.copyOf(next));
    }

    public void trace(String msg, Object... args) { emit(delegate.atTrace(), msg, args); }
    public void debug(String msg, Object... args) { emit(delegate.atDebug(), msg, args); }
    public void info(String msg, Object... args)  { emit(delegate.atInfo(),  msg, args); }
    public void warn(String msg, Object... args)  { emit(delegate.atWarn(),  msg, args); }
    public void error(String msg, Object... args) { emit(delegate.atError(), msg, args); }

    private void emit(LoggingEventBuilder builder, String msg, Object[] args) {
        // atXxx() returns a no-op builder when the level is disabled, so bound
        // fields cost nothing on suppressed levels.
        for (var e : fields.entrySet()) {
            builder = builder.addKeyValue(e.getKey(), e.getValue());
        }
        // log(msg, args) keeps standard SLF4J semantics: {} placeholder
        // substitution, and a trailing Throwable arg is still picked up as the cause.
        builder.log(msg, args);
    }
}
