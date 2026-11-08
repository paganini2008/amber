package com.github.amber.dialect.ir;

import com.github.amber.core.Dialect;
import com.github.amber.core.analysis.Lifecycles;
import com.github.amber.core.analysis.ObjectLifecycle;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.Script;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.core.lineage.ColumnEdge;
import com.github.amber.dialect.DialectFrontends;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.github.amber.dialect.ir.IrAssertions.first;
import static com.github.amber.dialect.ir.IrAssertions.kinds;
import static com.github.amber.dialect.ir.IrAssertions.lifecycle;
import static com.github.amber.dialect.ir.IrAssertions.reads;
import static com.github.amber.dialect.ir.IrAssertions.writes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IR and lineage of a Db2 CLP script with the {@code @} terminator.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class Db2IrTest {

    private static Script script;

    @BeforeAll
    static void build() throws IOException {
        script = IrAssertions.build(DialectFrontends.get(Dialect.DB2), "/sql/db2/ledger_complex.sql");
    }

    @Test
    void outlineAndObjects() {
        assertEquals(List.of(StatementKind.CREATE_TABLE, StatementKind.INSERT, StatementKind.MERGE,
                StatementKind.CREATE_PROCEDURE, StatementKind.CALL, StatementKind.DROP), kinds(script.statements()));
        assertEquals(List.of("CREATE TEMP_TABLE SESSION.TMP_POSTING"), writes(script.statements().get(0)));
        Statement insert = first(script.statements(), StatementKind.INSERT);
        assertEquals(List.of("INSERT TEMP_TABLE SESSION.TMP_POSTING"), writes(insert));
        assertEquals(List.of("FIN.ENTRY"), reads(insert));
        assertEquals(List.of("MERGE TABLE FIN.BALANCE"), writes(first(script.statements(), StatementKind.MERGE)));

        Statement proc = first(script.statements(), StatementKind.CREATE_PROCEDURE);
        assertEquals("FIN.CLOSE_DAY", proc.name().key());
        Statement body = first(proc.children(), StatementKind.BLOCK);
        assertTrue(kinds(body.children()).containsAll(List.of(StatementKind.EXCEPTION_HANDLER, StatementKind.LOOP,
                StatementKind.IF)), () -> kinds(body.children()).toString());
        Statement loop = first(body.children(), StatementKind.LOOP);
        assertEquals(List.of("FIN.BALANCE"), reads(first(loop.children(), StatementKind.SELECT)));
        assertEquals(List.of("INSERT TABLE FIN.OVERDRAFT"), writes(first(loop.children(), StatementKind.INSERT)));

        ObjectLifecycle tmp = lifecycle(Lifecycles.of(script), "SESSION.TMP_POSTING");
        assertEquals(ObjectKind.TEMP_TABLE, tmp.kind());
        assertEquals(ObjectLifecycle.Role.INTERMEDIATE, tmp.role());
    }

    @Test
    void lineage() {
        Statement insert = first(script.statements(), StatementKind.INSERT);
        Set<String> edges = insert.lineage().edges().stream().filter(ColumnEdge::isDirect)
                .map(e -> e.from() + " -> " + e.to() + " " + e.transform()).collect(Collectors.toSet());
        assertTrue(edges.containsAll(Set.of("fin.entry.amount -> day_entries.amount PASS_THROUGH",
                "day_entries.amount -> session.tmp_posting.amount AGGREGATE")), edges::toString);
        Statement merge = first(script.statements(), StatementKind.MERGE);
        assertTrue(merge.lineage().edges().stream().anyMatch(e -> e.isDirect()
                && e.from().toString().equals("p.amount") && e.to().toString().equals("fin.balance.amount")),
                () -> merge.lineage().edges().toString());
    }
}
