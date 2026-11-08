package com.github.amber.dialect.findings.rules;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Script;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.lineage.ColumnEdge;
import com.github.amber.core.lineage.ColumnNode;
import com.github.amber.core.meta.TableMeta;
import com.github.amber.core.source.SourceDecoder;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ParseOptions;
import com.github.amber.dialect.ScriptParse;
import com.github.amber.dialect.ScriptStatus;
import com.github.amber.dialect.findings.Finding;
import com.github.amber.dialect.ir.ScriptIrBuilder;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs the extra rules in strict mode over the whole public corpus, without metadata and with synthetic
 * metadata for every table the scripts use (columns from the lineage, alternating types, a primary key
 * and one index), so no rule throws on real-world SQL.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class RulesCorpusTest {

    private static final Path CORPUS = Path.of("src/test/resources/corpus");
    private static final String[] TYPES = {"varchar(20)", "int", "date", "numeric(10,2)"};

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
        Map<String, Integer> counts = new TreeMap<>();
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
            try {
                s = ScriptIrBuilder.build(f, p);
            } catch (RuntimeException | StackOverflowError e) {
                continue;                               // not this test's concern
            }
            for (Catalog c : List.of(new Catalog(), Catalog.of(synthetic(s)))) {
                try {
                    for (Finding x : ExtraRules.of(s, c, true)) {
                        counts.merge(x.rule(), 1, Integer::sum);
                    }
                } catch (RuntimeException | StackOverflowError e) {
                    StackTraceElement at = e.getStackTrace().length > 0 ? e.getStackTrace()[0] : null;
                    failures.add(CORPUS.relativize(file) + ": " + e + " at " + at);
                }
            }
        }
        System.out.println("Extra rules over the " + dialect.displayName() + " corpus (" + files.size() + " files): "
                + counts);
        assertEquals(List.of(), failures);
    }

    /** Metadata for every table owner in the lineage, with the columns the script uses. */
    static List<TableMeta> synthetic(Script s) {
        Map<String, Set<String>> tables = new LinkedHashMap<>();
        for (Statement st : s.flatten()) {
            if (st.lineage() == null) {
                continue;
            }
            for (ColumnEdge e : st.lineage().edges()) {
                for (ColumnNode n : List.of(e.from(), e.to())) {
                    if (n.ownerKind() == ColumnNode.OwnerKind.TABLE && !n.column().startsWith("*")) {
                        tables.computeIfAbsent(n.owner(), k -> new LinkedHashSet<>()).add(n.column());
                    }
                }
            }
        }
        List<TableMeta> out = new ArrayList<>();
        tables.forEach((owner, cols) -> {
            int dot = owner.lastIndexOf('.');
            List<TableMeta.ColumnMeta> metas = new ArrayList<>();
            int i = 0;
            for (String c : cols) {
                metas.add(new TableMeta.ColumnMeta(c, TYPES[i++ % TYPES.length], i % 2 == 0));
            }
            List<String> names = new ArrayList<>(cols);
            List<TableMeta.IndexMeta> ix = names.size() > 1
                    ? List.of(new TableMeta.IndexMeta("ix1", List.of(names.get(1)), false)) : List.of();
            out.add(new TableMeta(null, dot < 0 ? null : owner.substring(0, dot), dot < 0 ? owner
                    : owner.substring(dot + 1), "TABLE", metas, List.of(names.get(0)), List.of(), ix, -1, "synthetic"));
        });
        return out;
    }
}
