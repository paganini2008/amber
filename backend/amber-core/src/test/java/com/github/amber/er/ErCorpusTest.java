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
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds the ER model (strict: a statement failure fails the test) for every script of the public corpus,
 * with data flow and name matches on, and checks basic invariants: relationships point at entities,
 * columns pair up, occurrences have valid statements and ranges, and the Mermaid text names every entity.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class ErCorpusTest {

    private static final Path CORPUS = Path.of("src/test/resources/corpus");

    @TestFactory
    Stream<DynamicTest> corpus() {
        return Stream.of(Dialect.values())
                .filter(d -> Files.isDirectory(CORPUS.resolve(d.id())))
                .map(d -> DynamicTest.dynamicTest(d.displayName(), () -> run(d)));
    }

    private void run(Dialect dialect) throws Exception {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(CORPUS.resolve(dialect.id()))) {
            files = walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".sql")).sorted().toList();
        }
        DialectFrontend f = DialectFrontends.get(dialect);
        List<String> failures = new ArrayList<>();
        int relationships = 0;
        int joins = 0;
        for (Path file : files) {
            String text = SourceDecoder.decode(Files.readAllBytes(file)).text();
            if (text.length() > 400_000) {
                continue;
            }
            ScriptParse p = f.parse(new SourceText(file.getFileName().toString(), text), ParseOptions.FALLBACK);
            if (p.status() == ScriptStatus.FAILED) {
                continue;
            }
            Script s;
            Catalog c = new Catalog();
            try {
                s = ScriptIrBuilder.build(f, p, c);
            } catch (RuntimeException | StackOverflowError e) {
                continue;                               // not this test's concern
            }
            try {
                ErModel m = ErModelBuilder.build(s, c, new ErModelBuilder.Options(true, true, 100), true);
                check(m, s, text.length());
                relationships += m.relationships().size();
                if (System.getProperty("amber.er.dump") != null) {
                    for (ErModel.Relationship r : m.relationships()) {
                        System.out.println("ERDUMP " + dialect.id() + " " + file.getFileName() + " " + ErTestSupport.str(r));
                    }
                }
                joins += m.stats().joinEqualities();
            } catch (RuntimeException | AssertionError e) {
                failures.add(file.getFileName() + ": " + e);
            }
        }
        System.out.printf("ER corpus %s: %d files, %d relationships, %d join equalities%n", dialect.id(),
                files.size(), relationships, joins);
        assertEquals(List.of(), failures);
    }

    private static void check(ErModel m, Script s, int length) {
        Set<String> keys = new HashSet<>();
        m.entities().forEach(e -> assertTrue(keys.add(e.key()), "duplicate entity " + e.key()));
        int steps = s.flatten().size();
        for (ErModel.Relationship r : m.relationships()) {
            assertTrue(keys.contains(r.from().entity()) && keys.contains(r.to().entity()), r.toString());
            assertTrue(r.to().columns().isEmpty() || r.to().columns().size() == r.from().columns().size(), r.toString());
            assertTrue(r.count() >= r.occurrences().size(), r.toString());
            for (ErModel.Occurrence o : r.occurrences()) {
                assertTrue(o.statement() >= 0 && o.statement() < steps, r.toString());
                assertTrue(o.range().start().offset() <= o.range().end().offset() && o.range().end().offset() <= length);
            }
        }
        assertTrue(m.mermaid().startsWith("erDiagram"));
    }
}
