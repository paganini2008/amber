package com.github.amber.dialect.findings.rules;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Confidence;
import com.github.amber.core.meta.TableMeta;
import com.github.amber.dialect.findings.Finding;
import com.github.amber.dialect.findings.Finding.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.amber.dialect.findings.rules.RulesTestSupport.none;
import static com.github.amber.dialect.findings.rules.RulesTestSupport.one;
import static com.github.amber.dialect.findings.rules.RulesTestSupport.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AMB-P002 implicit conversion, per dialect, with and without metadata.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class ConversionRuleTest {

    private static final TableMeta ACCT = table(null, "acct", List.of("id"), List.of("acct_no"),
            "id number!", "acct_no varchar2(20)", "balance number(12,2)", "opened date", "name nvarchar2(40)");
    private static final TableMeta MS = table("dbo", "acct", List.of("id"), List.of("acct_no"),
            "id int!", "acct_no varchar(20)", "balance decimal(12,2)", "opened datetime", "day_on date",
            "uname nvarchar(40)");
    private static final TableMeta PG = table("public", "acct", List.of("id"), List.of(),
            "id integer!", "acct_no character varying(20)", "balance numeric", "opened date");

    @Test
    void characterColumnComparedWithANumber() {
        Finding f = one(Dialect.ORACLE, "select * from acct where acct_no = 12345;", "AMB-P002", ACCT);
        assertEquals(Severity.MEDIUM, f.severity());
        assertEquals(Confidence.CERTAIN, f.confidence());
        assertTrue(f.message().contains("index on it cannot be used"), f::message);
        assertNotNull(f.suggested());
        assertEquals("select * from acct where acct_no = '12345'", f.suggested().sql());
        assertEquals("REWRITE_OF_STATEMENT", f.suggested().kind().name());
        one(Dialect.SQLSERVER, "select * from dbo.acct a where 7 = a.acct_no;", "AMB-P002", MS);
        Finding pg = one(Dialect.POSTGRESQL, "select * from public.acct where acct_no in (1, 2);", "AMB-P002", PG);
        assertTrue(pg.message().contains("operator does not exist"), pg::message);
        none(Dialect.ORACLE, "select * from acct where acct_no = 12345;", "AMB-P002");
        none(Dialect.ORACLE, "select * from acct where acct_no = '12345' and balance > 10;", "AMB-P002", ACCT);
    }

    @Test
    void numberColumnComparedWithAString() {
        Finding low = one(Dialect.ORACLE, "select * from acct where balance = '10';", "AMB-P002", ACCT);
        assertEquals(Severity.LOW, low.severity());
        assertEquals("select * from acct where balance = 10", low.suggested().sql());
        Finding bad = one(Dialect.MYSQL, "select * from acct where balance = 'abc';", "AMB-P002",
                table(null, "acct", List.of("id"), List.of(), "id int!", "balance decimal(10,2)"));
        assertEquals(Severity.MEDIUM, bad.severity());
        assertTrue(bad.message().contains("becomes 0"), bad::message);
        assertNull(bad.suggested());
        none(Dialect.POSTGRESQL, "select * from public.acct where balance = '10';", "AMB-P002", PG);
    }

    @Test
    void dateColumnsAndStringLiterals() {
        Finding ora = one(Dialect.ORACLE, "select * from acct where opened >= '2024-01-31';", "AMB-P002", ACCT);
        assertTrue(ora.message().contains("NLS_DATE_FORMAT"), ora::message);
        assertEquals("select * from acct where opened >= DATE '2024-01-31'", ora.suggested().sql());
        Finding ms = one(Dialect.SQLSERVER, "select * from dbo.acct where opened >= '2024-01-31';", "AMB-P002", MS);
        assertEquals("select * from dbo.acct where opened >= '20240131';", ms.suggested().sql());
        none(Dialect.SQLSERVER, "select * from dbo.acct where opened >= '20240131' and day_on = '2024-01-31';",
                "AMB-P002", MS);
        Finding pg = one(Dialect.POSTGRESQL, "select * from public.acct where opened = '31/01/2024';", "AMB-P002", PG);
        assertNull(pg.suggested(), "a non-ISO date is not rewritten mechanically");
        none(Dialect.POSTGRESQL, "select * from public.acct where opened between '2024-01-01' and '2024-12-31';",
                "AMB-P002", PG);
    }

    @Test
    void nationalStringsAgainstVarcharColumns() {
        Finding f = one(Dialect.SQLSERVER, "select * from dbo.acct where acct_no = N'A-1';", "AMB-P002", MS);
        assertTrue(f.message().contains("national"), f::message);
        assertEquals("select * from dbo.acct where acct_no = 'A-1';", f.suggested().sql());
        none(Dialect.SQLSERVER, "select * from dbo.acct where uname = N'A-1';", "AMB-P002", MS);
    }

    @Test
    void declaredVariables() {
        String tsql = """
                create procedure p @no int as
                begin
                  select * from dbo.acct where acct_no = @no;
                end
                """;
        Finding f = one(Dialect.SQLSERVER, tsql, "AMB-P002", MS);
        assertTrue(f.message().contains("the variable @no"), f::message);
        assertNull(f.suggested());
        String plsql = """
                declare
                  v_no number := 5;
                  v_name varchar2(20);
                begin
                  update acct set balance = 0 where acct_no = v_no;
                  update acct set balance = 0 where acct_no = v_name;
                end;
                /
                """;
        Finding p = one(Dialect.ORACLE, plsql, "AMB-P002", ACCT);
        assertEquals(5, p.range().start().line());
        assertEquals(ConversionRule.Kind.OTHER, ConversionRule.kind("blob"));
        assertEquals(ConversionRule.Kind.OTHER, ConversionRule.kind(null));
        assertEquals(ConversionRule.Kind.STRING, ConversionRule.kind("character varying(10)"));
    }

    @Test
    void suppression() {
        none(Dialect.ORACLE, "-- amber:ignore AMB-P002\nselect * from acct where acct_no = 1;", "AMB-P002", ACCT);
        none(Dialect.SQLSERVER, "select * from dbo.acct where acct_no = 1 /* amber:ignore AMB-P002 */;", "AMB-P002", MS);
    }
}
