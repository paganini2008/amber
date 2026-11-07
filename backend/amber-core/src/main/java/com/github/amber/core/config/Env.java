package com.github.amber.core.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Engine configuration from {@code backend/.env} plus the process environment (which wins). The file is
 * only looked for where it belongs: {@code $AMBER_ENV_FILE}, or the {@code backend/} folder of the source
 * tree the engine runs from (the folder holding {@code pom.xml} above the engine's classes or jar). A
 * packaged app has no such folder, so it never picks up an unrelated {@code .env}. See
 * {@code backend/.env.example} for every key.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class Env {

    private static volatile Env instance;

    private final Map<String, String> file;
    private final Map<String, String> process;
    private final Path source;

    Env(Map<String, String> file, Map<String, String> process, Path source) {
        this.file = file;
        this.process = process;
        this.source = source;
    }

    /** The shared configuration, loaded once. */
    public static Env get() {
        Env e = instance;
        if (e == null) {
            synchronized (Env.class) {
                if (instance == null) {
                    Path p = locate(System.getenv("AMBER_ENV_FILE"));
                    instance = new Env(p == null ? Map.of() : read(p), System.getenv(), p);
                }
                e = instance;
            }
        }
        return e;
    }

    /** {@code .env} next to the source tree's {@code pom.xml}, or {@code explicit} when given. */
    static Path locate(String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            Path p = Path.of(explicit);
            return Files.isRegularFile(p) ? p : null;
        }
        Path found = null;
        try {
            found = search(Path.of(Env.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        } catch (Exception e) {
            // no code source (e.g. a custom class loader)
        }
        // Tests of other modules load amber-core from the local Maven repository: try the working directory too.
        return found != null ? found : search(Path.of(System.getProperty("user.dir", ".")).toAbsolutePath());
    }

    /** The backend source root's .env: a folder with .env, pom.xml and amber-core/, at most 6 levels up. */
    static Path search(Path start) {
        Path dir = start;
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            Path env = dir.resolve(".env");
            if (Files.isRegularFile(env) && Files.isRegularFile(dir.resolve("pom.xml"))
                    && Files.isDirectory(dir.resolve("amber-core"))) {
                return env;
            }
        }
        return null;
    }

    /** Parses {@code KEY=value} lines; {@code #} comments, optional {@code export }, single or double quotes. */
    static Map<String, String> read(Path p) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (String raw : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("export ")) {
                    line = line.substring(7).strip();
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).strip();
                String value = line.substring(eq + 1).strip();
                if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                        || value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                } else {
                    int hash = value.indexOf(" #");
                    if (hash >= 0) {
                        value = value.substring(0, hash).strip();   // trailing comment
                    }
                }
                out.put(key, value);
            }
        } catch (IOException e) {
            return Map.of();
        }
        return out;
    }

    /** The value of {@code key}: the process environment first, then {@code .env}; null when unset or blank. */
    public String value(String key) {
        String v = process.get(key);
        if (v == null || v.isBlank()) {
            v = file.get(key);
        }
        return v == null || v.isBlank() ? null : v;
    }

    public String value(String key, String fallback) {
        String v = value(key);
        return v == null ? fallback : v;
    }

    /** An integer setting; a missing or malformed value gives {@code fallback}. */
    public int integer(String key, int fallback) {
        String v = value(key);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** The {@code .env} file in use, or null. */
    public Path source() {
        return source;
    }

    /** Keys defined in the {@code .env} file (for diagnostics; values are not exposed). */
    public java.util.Set<String> fileKeys() {
        return Collections.unmodifiableSet(file.keySet());
    }
}
