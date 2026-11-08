package com.github.amber.er;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Script;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.source.SourceDecoder;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ParseOptions;
import com.github.amber.dialect.ScriptParse;
import com.github.amber.dialect.ScriptStatus;
import com.github.amber.dialect.ir.ScriptIrBuilder;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ER model never throws and stays fast on broken and extreme input: deterministic mutations of corpus
 * scripts (fallback parsing, strict model) and generated extremes (deep parentheses, very long AND / OR
 * chains, unterminated quotes and comments, thousands of aliases).
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class ErRobustnessTest {

    private static final Path CORPUS = Path.of("src/test/resources/corpus");

    private static ErModel build(Dialect d, String text) {
        DialectFrontend f = DialectFrontends.get(d);
        ScriptParse p = f.parse(SourceText.of(text), ParseOptions.FALLBACK);
        if (p.status() == ScriptStatus.FAILED) {
            return null;
        }
        Catalog c = new Catalog();
        Script s;
        try {
            s = ScriptIrBuilder.build(f, p, c);
        } catch (RuntimeException | StackOverflowError e) {
            return null;                                      // not this test's concern
        }
        return ErModelBuilder.build(s, c, new ErModelBuilder.Options(true, true, 50), true);
    }

    @Test
    void mutatedCorpusScripts() throws Exception {
        Random rnd = new Random(20261108);
        List<String> failures = new ArrayList<>();
        for (Dialect d : Dialect.values()) {
            Path dir = CORPUS.resolve(d.id());
            if (!Files.isDirectory(dir)) {
                continue;
            }
            List<Path> files;
            try (Stream<Path> walk = Files.walk(dir)) {
                files = walk.filter(p -> p.toString().endsWith(".sql")).sorted().limit(6).toList();
            }
            for (Path file : files) {
                String text = SourceDecoder.decode(Files.readAllBytes(file)).text();
                if (text.length() > 60_000) {
                    continue;
                }
                for (int i = 0; i < 4; i++) {
                    String mutant = mutate(text, rnd);
                    long t0 = System.nanoTime();
                    try {
                        build(d, mutant);
                    } catch (RuntimeException | StackOverflowError e) {
                        failures.add(d.id() + "/" + file.getFileName() + "#" + i + ": " + e);
                    }
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    assertTrue(ms < 20_000, file + " took " + ms + " ms");
                }
            }
        }
        assertEquals(List.of(), failures);
    }

    private static String mutate(String text, Random rnd) {
        StringBuilder sb = new StringBuilder(text);
        String[] inserts = {" on ", " where ", " and ", " or ", "(", ")", " = ", ".", "'", "\"", "/*", "--", " using (",
                " join ", ",", " case ", " end ", ";"};
        for (int k = 0; k < 6 && sb.length() > 0; k++) {
            int at = rnd.nextInt(sb.length());
            switch (rnd.nextInt(3)) {
                case 0 -> sb.insert(at, inserts[rnd.nextInt(inserts.length)]);
                case 1 -> sb.delete(at, Math.min(sb.length(), at + 1 + rnd.nextInt(12)));
                default -> {
                    int len = Math.min(sb.length() - at, 1 + rnd.nextInt(40));
                    sb.insert(at, sb.substring(at, at + len));
                }
            }
        }
        return sb.toString();
    }

    @Test
    void extremeConditions() {
        StringBuilder deep = new StringBuilder("select * from a_t a join b_t b on ");
        deep.append("(".repeat(400)).append("a.k = b.k").append(")".repeat(400)).append(";\n");
        StringBuilder ands = new StringBuilder("select * from a_t a, b_t b where a.k0 = b.k0");
        for (int i = 1; i < 3000; i++) {
            ands.append(" and a.k").append(i).append(" = b.k").append(i);
        }
        ands.append(";\n");
        StringBuilder nested = new StringBuilder("select * from a_t a join c_t c on ");
        for (int i = 0; i < 300; i++) {
            nested.append("(a.x").append(i).append(" = c.x").append(i).append(" and ");
        }
        nested.append("a.y = c.y").append(")".repeat(300)).append(";\n");
        StringBuilder ors = new StringBuilder("select * from a_t a join d_t d on a.k = d.k");
        for (int i = 0; i < 2000; i++) {
            ors.append(" or a.k").append(i).append(" = d.k").append(i);
        }
        ors.append(";\n");
        String sql = deep + ands.toString() + nested + ors;
        long t0 = System.nanoTime();
        ErModel m = build(Dialect.POSTGRESQL, sql);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 20_000, "took " + ms + " ms");
        assertTrue(m != null);
        List<ErModel.Relationship> ab = ErTestSupport.between(m, "A_T", "B_T");
        assertEquals(2, ab.size());
        assertTrue(ab.stream().anyMatch(r -> r.from().columns().size() == 3000));
        assertEquals(1, ErTestSupport.between(m, "A_T", "C_T").size());
        assertEquals(301, ErTestSupport.one(m, "A_T", "C_T").from().columns().size());
        assertTrue(ErTestSupport.between(m, "A_T", "D_T").isEmpty());
    }

    @Test
    void brokenText() {
        for (String s : List.of("select * from a x join b y on x.k = 'unterminated",
                "select * from a x join b y on x.k = y.k /* open comment",
                "select * from a x join b y on x.k = y.\"open",
                "update t set x = 1 where", "merge into t using s on (", "delete from t using",
                "select * from a join b using (", "select * from a x join b y on x.k = y.k(+",
                "select * from a join", "select * from a x, b y where x.k = y.k and")) {
            for (Dialect d : Dialect.values()) {
                build(d, s);                                   // must not throw
            }
        }
    }
}
