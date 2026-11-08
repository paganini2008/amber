package com.github.amber.er;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Script;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.meta.TableMeta;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ScriptParse;
import com.github.amber.dialect.ScriptStatus;
import com.github.amber.dialect.ir.ScriptIrBuilder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A large generated script: 500 tables (with metadata for half of them) and 5,000 joins in 1,000
 * statements. The model itself must take well under two seconds (parsing is measured apart).
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class ErPerformanceTest {

    @Test
    void fiveHundredTablesFiveThousandJoins() {
        Random random = new Random(42);
        StringBuilder sql = new StringBuilder();
        int joins = 0;
        for (int s = 0; s < 1000; s++) {
            int base = random.nextInt(500);
            sql.append("select t0.id from tab_").append(base).append(" t0");
            for (int j = 1; j <= 5; j++) {
                int other = random.nextInt(500);
                sql.append("\n  join tab_").append(other).append(" t").append(j).append(" on t").append(j)
                        .append(".id = t").append(j - 1).append(".tab_").append(other).append("_id");
                if (j % 2 == 0) {
                    sql.append(" and t").append(j).append(".kind = t0.kind");
                }
                joins++;
            }
            sql.append("\n where t0.status = 'A';\n");
        }
        List<TableMeta> meta = new ArrayList<>();
        for (int t = 0; t < 500; t += 2) {
            meta.add(ErTestSupport.table("tab_" + t, List.of("id"), "id", "kind", "status"));
        }
        DialectFrontend f = DialectFrontends.get(Dialect.ORACLE);
        long t0 = System.nanoTime();
        ScriptParse p = f.parse(SourceText.of(sql.toString()));
        assertEquals(ScriptStatus.SUCCESS, p.status());
        Catalog catalog = Catalog.of(meta).copy();
        Script script = ScriptIrBuilder.build(f, p, catalog);
        long parsed = System.nanoTime();
        ErModel m = ErModelBuilder.build(script, catalog, new ErModelBuilder.Options(true, true, 100), true);
        long built = System.nanoTime();
        long modelMs = (built - parsed) / 1_000_000;
        System.out.printf("ER performance: parse+IR %d ms, model %d ms, %d entities, %d relationships, %d equalities%n",
                (parsed - t0) / 1_000_000, modelMs, m.entities().size(), m.relationships().size(),
                m.stats().joinEqualities());
        assertEquals(joins + 2000, m.stats().joinEqualities());
        assertTrue(m.entities().size() >= 490, "entities: " + m.entities().size());
        assertTrue(modelMs < 2000, "model took " + modelMs + " ms");
        assertTrue(m.mermaid().length() > 10_000);
    }
}
