package com.github.amber.dialect.lineage;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Script;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.lineage.ColumnEdge;
import com.github.amber.core.lineage.ColumnNode;
import com.github.amber.core.lineage.LineageGraph;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ir.ScriptIrBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Column lineage for MySQL, MariaDB, SQLite and H2.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class OtherDialectsLineageTest {

    private static Set<String> edges(Dialect dialect, String sql) {
        DialectFrontend f = DialectFrontends.get(dialect);
        Script s = ScriptIrBuilder.build(f, f.parse(SourceText.of(sql)));
        return LineageGraph.of(s).edges().stream().map(LineageGraph.Edge::edge).filter(ColumnEdge::isDirect)
                .map(e -> e.from().ownerKind() + ":" + e.from() + " -> " + e.to() + " " + e.transform())
                .collect(Collectors.toSet());
    }

    private static Set<String> resource(Dialect dialect, String path) throws IOException {
        return edges(dialect, new String(OtherDialectsLineageTest.class.getResourceAsStream(path).readAllBytes(),
                StandardCharsets.UTF_8));
    }

    @Test
    void mySqlAndMariaDb() throws IOException {
        for (var d : new Dialect[] {Dialect.MYSQL, Dialect.MARIADB}) {
            Set<String> e = resource(d, "/sql/" + d.id() + "/stats_complex.sql");
            assertTrue(e.containsAll(Set.of(
                    "TABLE:retail.sale.amount -> recent.amount PASS_THROUGH",
                    "CTE:recent.amount -> tmp_sales.total AGGREGATE",
                    "TEMP_TABLE:tmp_sales.total -> retail.store.weekly_total PASS_THROUGH",
                    "TEMP_TABLE:tmp_sales.total -> retail.store_history.total PASS_THROUGH")), () -> d + " " + e);
        }
    }

    @Test
    void sqlite() throws IOException {
        Set<String> e = resource(Dialect.SQLITE, "/sql/sqlite/notes_complex.sql");
        assertTrue(e.containsAll(Set.of(
                "CTE:recent.owner_id -> tmp_active.owner_id PASS_THROUGH",
                "TABLE:main.app_user.email -> tmp_active.email PASS_THROUGH",
                "TABLE:main.digest.notes -> main.v_digest.notes PASS_THROUGH")), e::toString);
        assertTrue(e.stream().noneMatch(s -> s.contains("SUBQUERY")), "IN (SELECT …) is not a derived table");
    }

    @Test
    void sqliteUpdateAssignments() {
        Set<String> e = edges(Dialect.SQLITE, """
                update t set a = s.b, (c, d) = (s.x + 1, 2) from s where s.id = t.id;
                update t set a = (select max(b) from u where u.id = t.id) where t.k > 0;
                """);
        assertTrue(e.containsAll(Set.of(
                "TABLE:s.b -> t.a PASS_THROUGH",
                "TABLE:s.x -> t.c EXPRESSION",
                "TABLE:s.x -> t.d EXPRESSION",
                "TABLE:u.b -> t.a AGGREGATE")), e::toString);
    }

    @Test
    void h2() throws IOException {
        Set<String> e = resource(Dialect.H2, "/sql/h2/inventory_complex.sql");
        assertTrue(e.containsAll(Set.of(
                "TABLE:inv.movement.delta -> moves.qty AGGREGATE",
                "CTE:moves.sku -> tmp_stock.sku PASS_THROUGH",
                "TEMP_TABLE:tmp_stock.qty -> inv.stock.qty PASS_THROUGH")), e::toString);
        Set<String> merge = edges(Dialect.H2, "merge into s using t on s.k = t.k when matched then update set s.q = t.q "
                + "when not matched then insert (k, q) values (t.k, t.q);");
        assertTrue(merge.contains("TABLE:t.k -> s.k PASS_THROUGH") && !merge.contains("TABLE:t.q -> s.k PASS_THROUGH"),
                merge::toString);
        ColumnNode unknown = new ColumnNode(ColumnNode.OwnerKind.UNKNOWN, "?", "?", "x");
        assertTrue(unknown.key().startsWith("?."));
    }

    @Test
    void datePartsAndRecursiveCtesAreNotTables() {
        Set<String> e = edges(Dialect.MARIADB, """
                insert into t_age (id, age_h)
                with recursive tree as (select id, parent_id from q where parent_id is null
                                        union all select q.id, q.parent_id from q join tree on q.parent_id = tree.id)
                select tree.id, timestampdiff(HOUR, k.opened_at, now()) from tree join k on k.id = tree.id;
                """);
        assertTrue(e.stream().noneMatch(s -> s.contains("HOUR") || s.startsWith("TABLE:tree")), e::toString);
        assertTrue(e.contains("TABLE:k.opened_at -> t_age.age_h EXPRESSION"), e::toString);
        Set<String> ms = edges(Dialect.SQLSERVER, "insert into t (d) select dateadd(day, 1, o.created_at) from o;");
        assertEquals(Set.of("TABLE:o.created_at -> t.d EXPRESSION"), ms);
    }

    @Test
    void quotedNamesStayWhole() {
        DialectFrontend f = DialectFrontends.get(Dialect.MYSQL);
        var catalog = Catalog.of(List.of());
        ScriptIrBuilder.build(f, f.parse(SourceText.of("create table t (`my col` int, b int);")), catalog);
        assertEquals(List.of("my col", "b"), catalog.tables().get(0).columnNames());
        Set<String> ora = edges(Dialect.ORACLE, "insert into t2 (lvl) select \"LEVEL\" from t;");
        assertTrue(ora.contains("TABLE:t.LEVEL -> t2.lvl PASS_THROUGH"), ora::toString);
    }
}
