package com.github.amber.core.i18n;

import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.dialect.findings.RuleCatalog;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The message bundles (complete in every language, same placeholders) and the per-request language scope.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class MessagesTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)}");

    private static List<Integer> placeholders(String text) {
        List<Integer> out = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            out.add(Integer.parseInt(m.group(1)));
        }
        out.sort(null);
        return out;
    }

    @Test
    void everyBundleHasTheSameKeysAndPlaceholdersInEveryLanguage() {
        for (String bundle : Messages.BUNDLES) {
            Map<String, String> en = Messages.raw(bundle, "en");
            assertFalse(en.isEmpty(), "empty or missing bundle " + Messages.path(bundle, "en"));
            for (String tag : List.of("zh-CN", "es")) {
                Map<String, String> other = Messages.raw(bundle, tag);
                assertEquals(new TreeSet<>(en.keySet()), new TreeSet<>(other.keySet()), Messages.path(bundle, tag));
                for (var e : en.entrySet()) {
                    String translated = other.get(e.getKey());
                    assertFalse(translated.isBlank() && !e.getValue().isBlank(), tag + " " + e.getKey() + " is blank");
                    assertEquals(placeholders(e.getValue()), placeholders(translated),
                            tag + " " + e.getKey() + ": placeholders differ from English");
                }
            }
        }
    }

    @Test
    void englishPlaceholdersAreNumberedFromZeroWithoutGaps() {
        for (String bundle : Messages.BUNDLES) {
            Messages.raw(bundle, "en").forEach((key, text) -> {
                List<Integer> used = new ArrayList<>(new TreeSet<>(placeholders(text)));
                for (int i = 0; i < used.size(); i++) {
                    assertEquals(i, used.get(i), key + " skips {" + i + "}");
                }
            });
        }
    }

    @Test
    void keysAreUniqueAcrossBundlesAndPrefixedByArea() {
        Map<String, String> owner = new HashMap<>();
        for (String bundle : Messages.BUNDLES) {
            for (String key : Messages.raw(bundle, "en").keySet()) {
                String previous = owner.put(key, bundle);
                assertEquals(null, previous, key + " is in " + previous + " and " + bundle);
                assertTrue(key.startsWith(bundle + ".") || (bundle.equals("findings") && key.startsWith("rule.")),
                        key + " should start with " + bundle + ".");
            }
        }
    }

    @Test
    void chineseAndSpanishAreActuallyTranslated() {
        int zhSame = 0;
        int total = 0;
        for (String bundle : Messages.BUNDLES) {
            Map<String, String> en = Messages.raw(bundle, "en");
            Map<String, String> zh = Messages.raw(bundle, "zh-CN");
            for (var e : en.entrySet()) {
                total++;
                if (e.getValue().equals(zh.get(e.getKey()))) {
                    zhSame++;
                }
            }
        }
        assertTrue(zhSame * 10 < total, zhSame + " of " + total + " Chinese texts equal the English ones");
        assertNotEquals(Messages.en().text("rule.AMB-S001.title"), Messages.of("es").text("rule.AMB-S001.title"));
    }

    /** Every key written as a literal in the engine's code ({@code t("…")}, {@code text("…")}) exists. */
    @Test
    void everyLiteralKeyInTheCodeExists() throws IOException {
        Pattern call = Pattern.compile("\\b(?:t|text)\\(\\s*\"([a-z][A-Za-z0-9_.-]*)\"\\s*[,)]");
        List<String> missing = new ArrayList<>();
        int found = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = call.matcher(Files.readString(f));
                while (m.find()) {
                    found++;
                    if (!Messages.en().has(m.group(1))) {
                        missing.add(f.getFileName() + ": " + m.group(1));
                    }
                }
            }
        }
        assertTrue(found > 50, "only " + found + " keys found");
        assertEquals(List.of(), missing);
    }

    /** Keys the code builds from enum names and rule ids. */
    @Test
    void computedKeysExist() {
        Messages en = Messages.en();
        for (StatementKind k : StatementKind.values()) {
            assertTrue(en.has("summary." + k.name()), "summary." + k.name());
        }
        for (ObjectKind k : ObjectKind.values()) {
            assertTrue(en.has("summary.object." + k.name()), "summary.object." + k.name());
        }
        for (var r : RuleCatalog.all()) {
            assertTrue(en.has("rule." + r.id() + ".title") && en.has("rule." + r.id() + ".description"), r.id());
            if (!r.id().startsWith("AMB-P")) {
                assertTrue(en.has("rule." + r.id() + ".suggestion"), r.id());
            }
        }
        for (String dml : List.of("insert", "update", "delete", "merge")) {
            assertTrue(en.has("findings.S006.message." + dml));
        }
    }

    @Test
    void tagsAreNormalizedAndUnknownOnesFallBackToEnglish() {
        assertSame(Messages.en(), Messages.of(null));
        assertSame(Messages.en(), Messages.of(""));
        assertSame(Messages.en(), Messages.of("fr"));
        assertSame(Messages.en(), Messages.of("en-US"));
        assertSame(Messages.en(), Messages.of("zh-TW"));
        for (String zh : List.of("zh-CN", "zh_CN", "zh", "ZH-cn", "zh-Hans", "zh-Hans-CN", " zh-CN ")) {
            assertEquals("zh-CN", Messages.of(zh).tag(), zh);
        }
        for (String es : List.of("es", "es-ES", "es_MX", "es-419", "ES")) {
            assertEquals("es", Messages.of(es).tag(), es);
        }
        assertTrue(Messages.en().isEnglish());
        assertFalse(Messages.of("es").isEnglish());
        assertEquals(Messages.SUPPORTED, List.of("en", "zh-CN", "es"));
    }

    @Test
    void placeholdersAreReplacedAsPlainText() {
        Messages en = Messages.en();
        assertEquals("This subquery appears 1000 times in the script.", en.text("findings.D002.message", 1000));
        assertEquals("LIKE '$1\\x' cannot use an index on the column.", en.text("findings.S003.message", "'$1\\x'"));
        assertEquals("LIKE {0} cannot use an index on the column.", en.text("findings.S003.message"));
        assertEquals("no.such.key", en.text("no.such.key", 1));
        assertFalse(en.has("no.such.key"));
        assertEquals("这个子查询在脚本中出现了 3 次。", Messages.of("zh-CN").text("findings.D002.message", 3));
    }

    @Test
    void aMissingTranslationFallsBackToEnglish() {
        // the merged texts of every language start from English, so only keys unknown in English return the key
        for (String key : Messages.raw("findings", "en").keySet()) {
            assertTrue(Messages.of("es").has(key) && Messages.of("zh-CN").has(key), key);
        }
    }

    @Test
    void theLanguageIsScopedToTheCallAndRestoredAfterIt() throws Exception {
        assertSame(Messages.en(), Messages.current());
        String title = Messages.call(Messages.of("es"), () -> {
            String inner = Messages.call(Messages.of("zh-CN"), () -> Messages.t("rule.AMB-S001.title"));
            assertEquals("笛卡尔积", inner);
            return Messages.t("rule.AMB-S001.title");
        });
        assertEquals("Producto cartesiano", title);
        assertSame(Messages.en(), Messages.current());
        assertThrows(IllegalStateException.class, () -> Messages.call(Messages.of("es"), () -> {
            throw new IllegalStateException("boom");
        }));
        assertSame(Messages.en(), Messages.current(), "restored after a failure");
    }

    @Test
    void bindCarriesTheLanguageToAnotherThread() throws Exception {
        String title = Messages.call(Messages.of("zh-CN"), () -> CompletableFuture.supplyAsync(
                Messages.bind(() -> Messages.t("rule.AMB-S001.title"))).join());
        assertEquals("笛卡尔积", title);
        assertEquals("Cartesian product", CompletableFuture.supplyAsync(
                Messages.bind(() -> Messages.t("rule.AMB-S001.title"))).join());
        assertThrows(IllegalArgumentException.class, () -> Messages.bind(() -> {
            throw new IllegalArgumentException("boom");
        }).get());
        Set<String> seen = new TreeSet<>();
        Thread t = new Thread(() -> seen.add(Messages.current().tag()));
        t.start();
        t.join();
        assertEquals(Set.of("en"), seen, "a new thread starts in English");
    }
}
