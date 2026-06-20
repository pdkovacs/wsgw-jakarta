package io.github.pdkovacs.wsgw.e2e.app.logging;

import org.jboss.logging.Logger;
import org.jboss.logging.MDC;
import org.slf4j.helpers.MessageFormatter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An immutable JBoss-Logging wrapper that carries bound context fields and emits
 * them as JSON properties on every log line — the Go {@code slog.With} /
 * Node {@code pino.child} style, adapted to Quarkus.
 *
 * <p>This is the Quarkus counterpart of the gateway's {@code CtxLogger}. Quarkus
 * owns the logging backend (JBoss LogManager) and configures it from
 * {@code application.properties}, so Logback/logstash do not apply here. The
 * portable channel for structured fields into Quarkus's JSON output is the
 * {@link MDC}, which {@code quarkus-logging-json} serializes as top-level fields.
 *
 * <p>Bound fields live on this object, not in the MDC ThreadLocal: {@link #with}
 * returns a new instance, and the fields are pushed into the MDC only for the
 * duration of each emit, then restored. A bound logger can therefore be handed
 * to another thread (e.g. a fan-out executor) and still log its context — the
 * MDC handoff happens on whichever thread actually logs.
 */
public final class CtxLogger {

    private static final String FQCN = CtxLogger.class.getName();

    private final Logger delegate;
    private final Map<String, Object> fields;   // immutable, insertion-ordered

    private CtxLogger(Logger delegate, Map<String, Object> fields) {
        this.delegate = delegate;
        this.fields = fields;
    }

    public static CtxLogger of(Class<?> clazz) {
        return new CtxLogger(Logger.getLogger(clazz), Map.of());
    }

    public static CtxLogger of(String name) {
        return new CtxLogger(Logger.getLogger(name), Map.of());
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

    public void trace(String msg, Object... args) { emit(Logger.Level.TRACE, msg, args); }
    public void debug(String msg, Object... args) { emit(Logger.Level.DEBUG, msg, args); }
    public void info(String msg, Object... args)  { emit(Logger.Level.INFO,  msg, args); }
    public void warn(String msg, Object... args)  { emit(Logger.Level.WARN,  msg, args); }
    public void error(String msg, Object... args) { emit(Logger.Level.ERROR, msg, args); }

    private void emit(Logger.Level level, String msg, Object[] args) {
        if (!delegate.isEnabled(level)) {
            return;   // bound fields cost nothing on suppressed levels
        }
        // {}-placeholder formatting and trailing-Throwable extraction, matching
        // the gateway's SLF4J-based CtxLogger so call sites read identically.
        var formatted = MessageFormatter.arrayFormat(msg, args);

        var previous = new LinkedHashMap<String, Object>();
        for (var e : fields.entrySet()) {
            previous.put(e.getKey(), MDC.get(e.getKey()));
            MDC.put(e.getKey(), e.getValue());
        }
        try {
            // The fqcn-qualified overloads keep caller location pointing at the
            // real call site rather than this wrapper.
            switch (level) {
                case TRACE        -> delegate.trace(FQCN, formatted.getMessage(), formatted.getThrowable());
                case DEBUG        -> delegate.debug(FQCN, formatted.getMessage(), formatted.getThrowable());
                case INFO         -> delegate.info(FQCN, formatted.getMessage(), formatted.getThrowable());
                case WARN         -> delegate.warn(FQCN, formatted.getMessage(), formatted.getThrowable());
                case ERROR, FATAL -> delegate.error(FQCN, formatted.getMessage(), formatted.getThrowable());
            }
        } finally {
            previous.forEach((key, value) -> {
                if (value == null) {
                    MDC.remove(key);
                } else {
                    MDC.put(key, value);
                }
            });
        }
    }
}
