package com.github.amber.dialect;

import com.github.amber.core.Dialect;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Registry of the dialect frontends available on the class path.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class DialectFrontends {

    private static final Map<Dialect, DialectFrontend> FRONTENDS = load();

    private DialectFrontends() {
    }

    public static DialectFrontend get(Dialect dialect) {
        DialectFrontend frontend = FRONTENDS.get(dialect);
        if (frontend == null) {
            throw new IllegalStateException("No frontend on the class path for " + dialect.displayName());
        }
        return frontend;
    }

    public static Map<Dialect, DialectFrontend> all() {
        return FRONTENDS;
    }

    private static Map<Dialect, DialectFrontend> load() {
        Map<Dialect, DialectFrontend> map = new EnumMap<>(Dialect.class);
        for (DialectFrontend frontend : ServiceLoader.load(DialectFrontend.class)) {
            DialectFrontend previous = map.putIfAbsent(frontend.dialect(), frontend);
            if (previous != null) {
                throw new IllegalStateException("Two frontends for " + frontend.dialect() + ": "
                        + previous.getClass().getName() + ", " + frontend.getClass().getName());
            }
        }
        return Collections.unmodifiableMap(map);
    }
}
