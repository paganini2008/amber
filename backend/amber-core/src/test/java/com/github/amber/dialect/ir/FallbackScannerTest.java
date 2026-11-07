package com.github.amber.dialect.ir;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.Confidence;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.Script;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ParseOptions;
import com.github.amber.dialect.ScriptParse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class FallbackScannerTest {

    private static List<String> scan(Dialect dialect, String sql) {
        SourceText src = SourceText.of(sql);
        FallbackScanner.Result r = FallbackScanner.scan(dialect, src, src.range(0, sql.length()));
        return r.refs().stream().map(ref -> ref.access() + " " + ref.kind() + " " + ref.name().key()).toList();
    }

    private static StatementKind kind(Dialect dialect, String sql) {
        SourceText src = SourceText.of(sql);
        return FallbackScanner.scan(dialect, src, src.range(0, sql.length())).kind();
    }

    @Test
    void recognizesDmlPatterns() {
        assertEquals(List.of("INSERT TABLE DW.FACT", "READ TABLE STAGE.A", "READ TABLE STAGE.B"),
                scan(Dialect.ORACLE, "insert into dw.fact select ??? from stage.a join stage.b on"));
        assertEquals(StatementKind.INSERT, kind(Dialect.ORACLE, "insert into dw.fact ???"));
        assertEquals(List.of("UPDATE TABLE T", "READ TABLE S"), scan(Dialect.ORACLE, "update t set x = (select ! from s)"));
        assertEquals(List.of("DELETE TABLE T", "READ TABLE U"), scan(Dialect.ORACLE, "delete from t where id in (select id from u) ??"));
        assertEquals(List.of("MERGE TABLE TGT", "READ TABLE SRC"), scan(Dialect.ORACLE, "merge into tgt using src on (??)"));
        assertEquals(StatementKind.SELECT, kind(Dialect.ORACLE, "select ?? from x"));
    }

    @Test
    void recognizesDdlPatterns() {
        assertEquals(List.of("CREATE TEMP_TABLE #T"), scan(Dialect.SQLSERVER, "create temporary table #t (a int ???"));
        assertEquals(List.of("CREATE TABLE T"), scan(Dialect.POSTGRESQL, "create table if not exists T (a ??"));
        assertEquals(List.of("DROP TABLE T"), scan(Dialect.ORACLE, "drop table if exists t ???"));
        assertEquals(List.of("TRUNCATE TABLE T"), scan(Dialect.ORACLE, "truncate table t ???"));
        assertEquals(List.of("ALTER TABLE T"), scan(Dialect.ORACLE, "alter table t ??? add"));
        assertEquals(StatementKind.UNPARSED, kind(Dialect.ORACLE, "frobnicate the widget"));
        assertEquals(List.of("READ TABLE DUAL"), scan(Dialect.ORACLE, "select * from (select 1 from dual)"));
    }

    @Test
    void fallbackModeBuildsPartialStatementsInTheIr() {
        DialectFrontend oracle = DialectFrontends.get(Dialect.ORACLE);
        String sql = """
                create table t1 as select * from src;
                insert into t2 select a, , b from t1;
                drop table t1;
                """;
        ScriptParse strict = oracle.parse(SourceText.of(sql));
        assertThrows(IllegalArgumentException.class, () -> ScriptIrBuilder.build(oracle, strict));

        Script script = ScriptIrBuilder.build(oracle, oracle.parse(SourceText.of(sql), ParseOptions.FALLBACK));
        Statement broken = script.statements().get(1);
        assertEquals(StatementKind.INSERT, broken.kind());
        assertEquals(Confidence.PARTIAL, broken.confidence());
        ObjectRef read = broken.refs().stream().filter(r -> r.access() == Access.READ).findFirst().orElseThrow();
        assertEquals("T1", read.name().key());
        assertEquals(Confidence.PARTIAL, read.confidence());
        assertTrue(broken.summary().startsWith("Insert into T2") || broken.summary().startsWith("Insert into t2"), broken.summary());
        assertEquals(ObjectKind.TABLE, read.kind());
    }
}
