package com.github.amber.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.amber.core.Dialect;
import com.github.amber.core.source.SourceDecoder;
import com.github.amber.core.source.SourceText;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Files in every encoding a legacy script may come in (UTF-8 with and without BOM, UTF-16 LE/BE with and
 * without BOM, GB18030, Windows-1252) and broken byte sequences: {@link SourceDecoder} never throws, and the
 * engine's ranges (UTF-16 offsets, lines, columns) stay consistent with the decoded text when the file is
 * read through the {@code path} parameter like the app does.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class EncodingRobustTest {

    /** Accents, CJK and an emoji (a surrogate pair) before, inside and after statements. */
    private static final String SCRIPT = "-- résumé 中文 😀\n"
            + "INSERT INTO t2 (a, b) SELECT a, 'été € 😀' FROM t1 WHERE c = '中';\n"
            + "/* 😀😀 */ UPDATE t2 SET b = 'x' WHERE a = 1;\r\n"
            + "SELECT a FROM t2;\r";

    private static final Duration WATCHDOG = Duration.ofSeconds(60);

    @Test
    void everyEncodingDecodesToTheSameText(@TempDir Path dir) throws Exception {
        String gbText = SCRIPT.replace("😀", "中");      // GB18030 can hold emoji, but keep it CJK
        String latinText = "SELECT 'café € ü' AS x FROM t;\n";
        record Case(String name, byte[] bytes, String expected, String charset, boolean bom) {
        }
        List<Case> cases = List.of(
                new Case("utf-8", SCRIPT.getBytes(StandardCharsets.UTF_8), SCRIPT, "UTF-8", false),
                new Case("utf-8 bom", concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                        SCRIPT.getBytes(StandardCharsets.UTF_8)), SCRIPT, "UTF-8", true),
                new Case("utf-16le bom", concat(new byte[]{(byte) 0xFF, (byte) 0xFE},
                        SCRIPT.getBytes(StandardCharsets.UTF_16LE)), SCRIPT, "UTF-16LE", true),
                new Case("utf-16be bom", concat(new byte[]{(byte) 0xFE, (byte) 0xFF},
                        SCRIPT.getBytes(StandardCharsets.UTF_16BE)), SCRIPT, "UTF-16BE", true),
                new Case("utf-16le without bom", SCRIPT.getBytes(StandardCharsets.UTF_16LE), SCRIPT, "UTF-16LE", false),
                new Case("utf-16be without bom", SCRIPT.getBytes(StandardCharsets.UTF_16BE), SCRIPT, "UTF-16BE", false),
                new Case("gb18030", gbText.getBytes(SourceDecoder.GB18030), gbText, "GB18030", false));
        for (Case c : cases) {
            SourceDecoder.Decoded d = SourceDecoder.decode(c.bytes());
            assertEquals(c.expected(), d.text(), c.name());
            assertEquals(c.charset(), d.charset().name(), c.name());
            assertEquals(c.bom(), d.bom(), c.name());
            Path file = dir.resolve(c.name().replace(' ', '_') + ".sql");
            Files.write(file, c.bytes());
            assertNull(analyzeFile(file, null, d.text()), c.name());
        }
        // Windows-1252 is not detected (it would be read as GB18030), but works when the app names it
        byte[] cp1252 = latinText.getBytes(Charset.forName("windows-1252"));
        assertFalse(SourceDecoder.decode(cp1252).text().isEmpty());
        assertEquals(latinText, SourceDecoder.decode(cp1252, Charset.forName("windows-1252")).text());
        Path file = dir.resolve("cp1252.sql");
        Files.write(file, cp1252);
        assertNull(analyzeFile(file, "windows-1252", latinText));
        assertNull(analyzeFile(file, null, SourceDecoder.decode(cp1252).text()));
    }

    @Test
    void brokenBytesNeverThrow(@TempDir Path dir) throws Exception {
        List<byte[]> inputs = new ArrayList<>();
        byte[] utf8 = SCRIPT.getBytes(StandardCharsets.UTF_8);
        inputs.add(new byte[0]);
        inputs.add(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});                 // BOM only
        inputs.add(new byte[]{(byte) 0xFF, (byte) 0xFE, 0x41});                          // UTF-16 BOM, odd length
        inputs.add(new byte[]{(byte) 0xFE, (byte) 0xFF, (byte) 0xD8, 0x00, 0x00, 0x41}); // lone high surrogate
        inputs.add(new byte[]{(byte) 0xC0, (byte) 0xAF, 0x27, (byte) 0xE0, (byte) 0x80, (byte) 0xAF}); // overlong
        inputs.add(new byte[]{(byte) 0xED, (byte) 0xA0, (byte) 0x80});                  // UTF-8 encoded surrogate
        inputs.add(new byte[]{(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80});    // beyond U+10FFFF
        inputs.add(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        inputs.add(Arrays.copyOf(utf8, utf8.length - 2));                      // cut inside the emoji
        inputs.add(concat(utf8, new byte[]{(byte) 0x80, 0x0A, (byte) 0xFE}));
        byte[] gb = SCRIPT.replace("😀", "中").getBytes(SourceDecoder.GB18030);
        inputs.add(Arrays.copyOf(gb, gb.length - 1));                          // GB18030 cut mid-character
        Random rnd = new Random(20261108);
        for (int k = 0; k < 40; k++) {
            byte[] b = utf8.clone();
            for (int n = 0; n < 1 + rnd.nextInt(8); n++) {
                b[rnd.nextInt(b.length)] = (byte) rnd.nextInt(256);
            }
            inputs.add(b);
            byte[] noise = new byte[rnd.nextInt(400)];
            rnd.nextBytes(noise);
            inputs.add(noise);
        }
        int k = 0;
        for (byte[] bytes : inputs) {
            String text = SourceDecoder.decode(bytes).text();
            for (String cs : new String[]{"UTF-8", "UTF-16LE", "GB18030", "windows-1252", "ISO-8859-1"}) {
                SourceDecoder.decode(bytes, Charset.forName(cs));
            }
            Path file = dir.resolve("broken" + (k++) + ".sql");
            Files.write(file, bytes);
            assertNull(analyzeFile(file, null, text), () -> "input " + HexFormat.of().formatHex(bytes));
        }
    }

    @Test
    void offsetsAreUtf16IndexesAndColumnsCountUtf16Units() throws Exception {
        ObjectNode p = Robust.params(Dialect.ORACLE, SCRIPT, false);
        Robust.Outcome o = Robust.call("analyze", p, WATCHDOG);
        assertTrue(o.ok(), String.valueOf(o.error()));
        JsonNode st = o.json().path("statements");
        assertEquals(3, st.size());
        assertEquals(SCRIPT.indexOf("INSERT"), st.get(0).path("range").path("start").path("offset").asInt());
        int update = SCRIPT.indexOf("UPDATE");
        JsonNode start = st.get(1).path("range").path("start");
        assertEquals(update, start.path("offset").asInt());
        assertEquals(3, start.path("line").asInt());
        assertEquals(update - SCRIPT.lastIndexOf('\n', update), start.path("column").asInt());   // 2 emoji = 4 units
        assertEquals(4, st.get(2).path("range").path("start").path("line").asInt());                  // after \r\n
        assertNull(Robust.checkRanges(o.json(), Robust.Texts.of(SCRIPT)));
    }

    @Test
    void badEncodingNamesAreInvalidParams(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.sql");
        Files.writeString(file, "select 1 from dual;");
        for (String name : new String[]{"no-such-charset", "", "UTF 8"}) {
            ObjectNode p = Robust.MAPPER.createObjectNode();
            p.put("dialect", "oracle");
            p.put("path", file.toString());
            p.put("encoding", name);
            Robust.Outcome o = Robust.call("analyze", p, WATCHDOG);
            assertTrue(o.error() instanceof IllegalArgumentException, name + ": " + o.error());
        }
    }

    /** Analyzes {@code file} through {@code path}; null when every range is consistent with {@code text}. */
    private static String analyzeFile(Path file, String encoding, String text) {
        for (Dialect d : new Dialect[]{Dialect.ORACLE, Dialect.SQLSERVER, Dialect.MYSQL}) {
            ObjectNode p = Robust.MAPPER.createObjectNode();
            p.put("dialect", d.id());
            p.put("path", file.toString());
            p.put("allowFallback", true);
            if (encoding != null) {
                p.put("encoding", encoding);
            }
            Robust.Outcome o = Robust.call("analyze", p, WATCHDOG);
            if (!o.ok()) {
                return d + ": " + (o.hung() ? "hang" : Robust.describe(o.error()));
            }
            if (o.json().path("lineCount").asInt() != SourceText.of(text).lineCount()) {
                return d + ": lineCount " + o.json().path("lineCount");
            }
            String bad = Robust.checkRanges(o.json(), Robust.Texts.of(text));
            if (bad == null) {
                bad = Robust.checkAnalysis(o.json());
            }
            if (bad != null) {
                return d + ": " + bad;
            }
        }
        return null;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(a);
        out.writeBytes(b);
        return out.toByteArray();
    }
}
