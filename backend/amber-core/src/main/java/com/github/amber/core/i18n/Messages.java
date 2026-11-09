package com.github.amber.core.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The engine's user-facing text in one language: English (default), Simplified Chinese or Spanish.
 *
 * <p>Texts live in UTF-8 {@code .properties} bundles under {@code amber/i18n/} (one file per area and
 * language: {@code findings.properties}, {@code findings_zh_CN.properties}, {@code findings_es.properties}
 * …). The English files are the reference: a key missing in another language falls back to English.
 * Placeholders are {@code {0}}, {@code {1}} …, replaced by the arguments as plain strings (no
 * {@link java.text.MessageFormat} quoting rules and no locale number formatting, so {@code '} needs no
 * escaping and {@code 1000} stays {@code 1000}).
 *
 * <p>The language of a request is scoped to the thread that runs it: the server calls
 * {@link #call(Messages, Callable)} around a handler, which sets it and restores the previous one in a
 * {@code finally}. Work handed to another thread captures it with {@link #bind(Supplier)}. Code that
 * runs outside any scope (CLI, tests, warmup) gets English.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
public final class Messages {

    /** Language tags the engine understands; the first is the default. */
    public static final List<String> SUPPORTED = List.of("en", "zh-CN", "es");

    /** Bundle base names under {@code amber/i18n/}, one per area. */
    static final List<String> BUNDLES = List.of("summary", "findings", "rules", "rewrite", "diff", "ai", "doc", "er", "db");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)}");
    private static final ThreadLocal<Messages> CURRENT = new ThreadLocal<>();
    private static final Map<String, Messages> BY_TAG = load();
    private static final Messages EN = BY_TAG.get("en");

    private final String tag;
    private final Map<String, String> texts;

    private Messages(String tag, Map<String, String> texts) {
        this.tag = tag;
        this.texts = texts;
    }

    /** English, the default. */
    public static Messages en() {
        return EN;
    }

    /**
     * The messages for a language tag: {@code en}, {@code zh-CN} (also {@code zh}, {@code zh_CN},
     * {@code zh-Hans}) or {@code es} (also {@code es-ES}, {@code es-419} …); null, blank or unknown tags give
     * English.
     */
    public static Messages of(String tag) {
        return BY_TAG.get(normalize(tag));
    }

    /** The supported tag for a requested one ({@code en} when unknown). */
    public static String normalize(String tag) {
        if (tag == null) {
            return "en";
        }
        String t = tag.strip().replace('_', '-').toLowerCase(Locale.ROOT);
        if (t.equals("zh") || t.startsWith("zh-cn") || t.startsWith("zh-hans") || t.equals("zh-sg")) {
            return "zh-CN";
        }
        if (t.equals("es") || t.startsWith("es-")) {
            return "es";
        }
        return "en";
    }

    /** The messages of the current request (English outside any {@link #call} scope). */
    public static Messages current() {
        Messages m = CURRENT.get();
        return m == null ? EN : m;
    }

    /** Shorthand for {@code current().text(key, args)}. */
    public static String t(String key, Object... args) {
        return current().text(key, args);
    }

    /** Runs {@code body} with {@code messages} as the current language, restoring the previous one after. */
    public static <T> T call(Messages messages, Callable<T> body) throws Exception {
        Messages previous = CURRENT.get();
        CURRENT.set(messages);
        try {
            return body.call();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** A supplier that runs {@code body} in the language current now, on whatever thread calls it later. */
    public static <T> Supplier<T> bind(Supplier<T> body) {
        Messages captured = current();
        return () -> {
            try {
                return call(captured, body::get);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);       // unreachable: Supplier.get throws no checked exceptions
            }
        };
    }

    /** The language tag: {@code en}, {@code zh-CN} or {@code es}. */
    public String tag() {
        return tag;
    }

    public boolean isEnglish() {
        return this == EN;
    }

    /**
     * The text of {@code key} with {@code {n}} replaced by {@code args[n]} ({@link String#valueOf}); the key
     * itself when it is unknown (a missing text must never fail an analysis).
     */
    public String text(String key, Object... args) {
        String pattern = texts.get(key);
        if (pattern == null) {
            return key;
        }
        if (args == null || args.length == 0 || pattern.indexOf('{') < 0) {
            return pattern;
        }
        Matcher m = PLACEHOLDER.matcher(pattern);
        StringBuilder sb = new StringBuilder(pattern.length() + 32);
        while (m.find()) {
            int i = Integer.parseInt(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(i < args.length ? String.valueOf(args[i]) : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Whether {@code key} has a text (in English at least). */
    public boolean has(String key) {
        return texts.containsKey(key);
    }

    /** The raw texts of one bundle file of one language, without the English fallback; for tests. */
    static Map<String, String> raw(String bundle, String tag) {
        return read(path(bundle, tag));
    }

    static String path(String bundle, String tag) {
        String suffix = switch (tag) {
            case "zh-CN" -> "_zh_CN";
            case "es" -> "_es";
            default -> "";
        };
        return "amber/i18n/" + bundle + suffix + ".properties";
    }

    private static Map<String, Messages> load() {
        Map<String, String> en = new LinkedHashMap<>();
        for (String b : BUNDLES) {
            en.putAll(read(path(b, "en")));
        }
        Map<String, Messages> out = new LinkedHashMap<>();
        for (String tag : SUPPORTED) {
            Map<String, String> texts = new LinkedHashMap<>(en);
            if (!tag.equals("en")) {
                for (String b : BUNDLES) {
                    texts.putAll(read(path(b, tag)));
                }
            }
            out.put(tag, new Messages(tag, Collections.unmodifiableMap(texts)));
        }
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, String> read(String path) {
        Map<String, String> map = new LinkedHashMap<>();
        try (InputStream in = Messages.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                return map;
            }
            Properties p = new Properties();
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                p.load(r);
            }
            for (String k : p.stringPropertyNames()) {
                map.put(k, p.getProperty(k));
            }
            return map;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
    }
}
