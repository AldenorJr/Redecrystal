package com.redecrystal.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin logging façade for the SDK and plugins. Delegates to SLF4J so the host
 * server's logging backend (Paper/Velocity) is used when present, with a
 * consistent {@code RedeCrystal} naming prefix.
 */
public final class CrystalLogger {

    private final Logger delegate;

    private CrystalLogger(Logger delegate) {
        this.delegate = delegate;
    }

    public static CrystalLogger of(String name) {
        return new CrystalLogger(LoggerFactory.getLogger("RedeCrystal." + name));
    }

    public static CrystalLogger of(Class<?> type) {
        return of(type.getSimpleName());
    }

    public void info(String msg, Object... args)  { delegate.info(msg, args); }
    public void warn(String msg, Object... args)  { delegate.warn(msg, args); }
    public void debug(String msg, Object... args) { delegate.debug(msg, args); }
    public void error(String msg, Throwable t)    { delegate.error(msg, t); }
    public void error(String msg, Object... args) { delegate.error(msg, args); }
}
