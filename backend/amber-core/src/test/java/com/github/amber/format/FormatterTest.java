package com.github.amber.format;

import com.github.amber.core.Dialect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour of {@link Formatter}: layout rules, options, safety fallbacks and range formatting.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class FormatterTest {

    private static String fmt(Dialect d, String sql) {
        FormatResult r = Formatter.format(d, sql);
        assertEquals(r.text(), Formatter.format(d, r.text()).text(), "idempotent");
        assertNull(FormatCorpusTest.sameUnits(d, sql, r.text()));
        return r.text();
    }

    private static String fmt(Dialect d, String sql, FormatOptions o) {
        FormatResult r = Formatter.format(d, sql, o);
        assertEquals(r.text(), Formatter.format(d, r.text(), o).text(), "idempotent");
        return r.text();
    }

    @Test
    void shortSelectStaysCompactButClausesGetTheirOwnLines() {
        assertEquals("""
                SELECT a, b
                FROM t
                WHERE a = 1
                    AND b = 2
                ORDER BY a;""", fmt(Dialect.POSTGRESQL, "select a,b from t where a=1 and b=2 order by a;"));
    }

    @Test
    void lineCommentIsNeverJoinedWithFollowingCode() {
        String out = fmt(Dialect.ORACLE, "select a -- first\n, b /* mid */ , c from t -- end\nwhere x = 1;");
        assertEquals("""
                SELECT
                    a -- first
                    , b /* mid */ ,
                    c
                FROM t -- end
                WHERE x = 1;""", out);
    }

    @Test
    void ownLineCommentsTakeTheIndentOfTheCodeTheyPrecede() {
        String out = fmt(Dialect.POSTGRESQL, """
                select a,
                -- the b column
                b, c, d from t
                  -- filter
                where a > 0;""");
        assertEquals("""
                SELECT
                    a,
                    -- the b column
                    b,
                    c,
                    d
                FROM t
                -- filter
                WHERE a > 0;""", out);
    }

    @Test
    void stringsAndQuotedNamesAreCopiedExactly() {
        String sql = "select 'it''s -- ; not code', \"Mixed Case\", q'[a ' b]' from \"T\" where x = '  spaced  ';";
        String out = fmt(Dialect.ORACLE, sql);
        assertTrue(out.contains("'it''s -- ; not code'"));
        assertTrue(out.contains("q'[a ' b]'"));
        assertTrue(out.contains("'  spaced  '"));
        assertTrue(out.contains("\"Mixed Case\""));
    }

    @Test
    void keywordCaseOptions() {
        String sql = "Select Name, Count(*) From Emp Where Dept_Id In (1, 2) Group By Name;";
        assertEquals("""
                select Name, Count(*)
                from Emp
                where Dept_Id in (1, 2)
                group by Name;""", fmt(Dialect.ORACLE, sql, FormatOptions.DEFAULTS.withKeywordCase(FormatOptions.KeywordCase.LOWER)));
        assertEquals("""
                Select Name, Count(*)
                From Emp
                Where Dept_Id In (1, 2)
                Group By Name;""", fmt(Dialect.ORACLE, sql, FormatOptions.DEFAULTS.withKeywordCase(FormatOptions.KeywordCase.PRESERVE)));
    }

    @Test
    void wordsGluedToDotsOrSigilsKeepTheirCase() {
        assertEquals("SELECT t.select, x.end\nFROM t;", fmt(Dialect.POSTGRESQL, "select t.select, x.end from t;"));
    }

    @Test
    void leadingCommasAndIndentSize() {
        FormatOptions o = FormatOptions.DEFAULTS.withCommaStyle(FormatOptions.CommaStyle.LEADING).withIndentSize(2);
        assertEquals("""
                SELECT
                  a
                  , b
                  , c
                  , d
                FROM t;""", fmt(Dialect.SQLITE, "select a, b, c, d from t;", o));
    }

    @Test
    void narrowWidthBreaksFunctionArgumentsAndPacksAtomLists() {
        FormatOptions o = FormatOptions.DEFAULTS.withMaxLineWidth(30);
        assertEquals("""
                SELECT COALESCE(
                    given_name,
                    family_nm,
                    'unknown'
                )
                FROM t
                WHERE id IN (
                    1, 2, 3, 4, 5, 6, 7, 8, 9,
                    10
                );""", fmt(Dialect.MYSQL, "select coalesce(given_name, family_nm, 'unknown') from t where id in (1,2,3,4,5,6,7,8,9,10);", o));
    }

    @Test
    void blankLinesBetweenStatementsAreKeptButCollapsed() {
        String out = fmt(Dialect.H2, "select 1;\n\n\n\n\nselect 2;\n\nselect 3;   \n");
        assertEquals("SELECT 1;\n\nSELECT 2;\n\nSELECT 3;\n", out);
    }

    @Test
    void statementsOnOneLineAreSplitOntoTheirOwnLines() {
        assertEquals("SELECT 1;\nSELECT 2;", fmt(Dialect.MARIADB, "select 1; select 2;"));
    }

    @Test
    void crlfLineEndingsArePreserved() {
        String out = fmt(Dialect.SQLSERVER, "select a\r\nfrom t\r\nwhere a = 1 and b = 2\r\nGO\r\n");
        assertEquals("SELECT a\r\nFROM t\r\nWHERE a = 1\r\n    AND b = 2\r\nGO\r\n", out);
    }

    @Test
    void unterminatedStringKeepsTheStatementAsWritten() {
        String sql = "select a from t;\nselect 'oops from t";
        FormatResult r = Formatter.format(Dialect.POSTGRESQL, sql);
        assertEquals("SELECT a\nFROM t;\nselect 'oops from t", r.text());
        assertEquals(1, r.warnings().size());
        assertEquals(1, r.skipped().size());
        assertEquals(2, r.skipped().get(0).start().line());
    }

    @Test
    void wrappedAndJavaSourcesAreLeftAlone() {
        String wrapped = "create or replace package body p wrapped\na000000\nb2\n  abcd\n/\n";
        assertEquals(wrapped, Formatter.format(Dialect.ORACLE, wrapped).text());
        String java = "create or replace and compile java source named \"Hello\" as\npublic class Hello { // hi\n  int x;\n}\n/\n";
        FormatResult r = Formatter.format(Dialect.ORACLE, java);
        assertEquals(java, r.text());
        assertFalse(r.changed());
    }

    @Test
    void sqlServerGoWordIsNeverMovedToTheStartOfALine() {
        String sql = "select a, b, c, go from t\nGO\n";
        FormatResult r = Formatter.format(Dialect.SQLSERVER, sql);
        assertEquals(sql, r.text());
        assertEquals(1, r.warnings().size());
        assertTrue(r.warnings().get(0).contains("split"), r.warnings().toString());
    }

    @Test
    void substitutionVariablesAndDatabaseLinksKeepTheirShape() {
        String out = fmt(Dialect.ORACLE, "select * from emp@&&link. e where e.id=&id and e.d=&1;");
        assertEquals("""
                SELECT *
                FROM emp@&&link. e
                WHERE e.id = &id
                    AND e.d = &1;""", out);
    }

    @Test
    void mysqlDoubleDashWithoutSpaceIsArithmetic() {
        assertEquals("SELECT 5--1, a\nFROM t;", fmt(Dialect.MYSQL, "select 5--1, a from t;"));
    }

    @Test
    void postgresDollarBodiesAreNotTouched() {
        String body = "$fn$\nbegin\n   return   1;\nend\n$fn$";
        String out = fmt(Dialect.POSTGRESQL, "create function f() returns int as " + body + " language plpgsql;");
        assertTrue(out.contains(body));
    }

    @Test
    void createTableHasOneColumnPerLine() {
        assertEquals("""
                CREATE TABLE t (
                    id INT NOT NULL,
                    name VARCHAR(20),
                    PRIMARY KEY (id)
                );""", fmt(Dialect.MYSQL, "create table t (id int not null, name varchar(20), primary key (id));"));
    }

    @Test
    void tsqlIfElseWithoutBeginIndentsTheSingleStatement() {
        assertEquals("""
                IF @a = 1
                    PRINT 'one'
                ELSE IF @a = 2
                    PRINT 'two'
                ELSE
                BEGIN
                    PRINT 'other'
                END""", fmt(Dialect.SQLSERVER, "if @a = 1 print 'one' else if @a = 2 print 'two' else begin print 'other' end"));
    }

    @Test
    void sqlitTriggerBodyIsABlock() {
        assertEquals("""
                CREATE TRIGGER trg
                AFTER INSERT ON t
                BEGIN
                    UPDATE c
                    SET n = n + 1;
                END;""", fmt(Dialect.SQLITE, "create trigger trg after insert on t begin update c set n = n + 1; end;"));
    }

    @Test
    void transactionBeginIsAStatementNotABlock() {
        assertEquals("BEGIN;\nUPDATE t\nSET a = 1;\nCOMMIT;", fmt(Dialect.POSTGRESQL, "begin; update t set a = 1; commit;"));
    }

    @Test
    void repeatAndLabelsInMariaDb() {
        assertEquals("""
                CREATE PROCEDURE p()
                BEGIN
                    lbl: REPEAT
                        SET @i = @i + 1;
                    UNTIL @i > 3
                    END REPEAT lbl;
                END;""", fmt(Dialect.MARIADB, "create procedure p() begin lbl: repeat set @i = @i + 1; until @i > 3 end repeat lbl; end;"));
    }

    @Test
    void longProceduralConditionBreaksBeforeAndOr() {
        String out = fmt(Dialect.ORACLE, "begin if some_long_variable_name > 100 and another_long_variable_name < 200 "
                + "or yet_another_quite_long_variable_name = 3 then null; end if; end;",
                FormatOptions.DEFAULTS.withMaxLineWidth(60));
        assertEquals("""
                BEGIN
                    IF some_long_variable_name > 100
                        AND another_long_variable_name < 200
                        OR yet_another_quite_long_variable_name = 3 THEN
                        NULL;
                    END IF;
                END;""", out);
    }

    @Test
    void formatRangeOnlyTouchesOverlappingStatements() {
        String sql = "select a from t;\n    select b, c from u where x = 1;\nselect d from v;\n";
        int start = sql.indexOf("select b");
        FormatResult r = Formatter.formatRange(Dialect.POSTGRESQL, sql, start, start + 3, FormatOptions.DEFAULTS);
        assertEquals("select a from t;\n    SELECT b, c\n    FROM u\n    WHERE x = 1;\nselect d from v;\n", r.text());
        FormatResult caret = Formatter.formatRange(Dialect.POSTGRESQL, sql, 2, 2, FormatOptions.DEFAULTS);
        assertEquals("SELECT a\nFROM t;\n    select b, c from u where x = 1;\nselect d from v;\n", caret.text());
        assertThrows(IllegalArgumentException.class,
                () -> Formatter.formatRange(Dialect.POSTGRESQL, sql, 5, 2, FormatOptions.DEFAULTS));
    }

    @Test
    void clientCommandsAreCopiedVerbatim() {
        String sql = "SET   linesize 200\nprompt   it's   here\nselect 1 from dual;\n@  script.sql\n";
        assertEquals("SET   linesize 200\nprompt   it's   here\nSELECT 1\nFROM dual;\n@  script.sql\n",
                fmt(Dialect.ORACLE, sql));
    }

    @Test
    void byteOrderMarkIsKeptInFront() {
        FormatResult r = Formatter.format(Dialect.SQLSERVER, "\uFEFFselect a from t\nGO\nselect 'x");
        assertEquals("\uFEFFSELECT a\nFROM t\nGO\nselect 'x", r.text());
        assertEquals(1, r.skipped().size());
        assertEquals(20, r.skipped().get(0).start().offset());
        FormatResult range = Formatter.formatRange(Dialect.SQLSERVER, "\uFEFFselect a from t", 0, 0, FormatOptions.DEFAULTS);
        assertEquals("\uFEFFSELECT a\nFROM t", range.text());
    }

    @Test
    void adjacentStringLiteralsOnSeparateLinesStaySeparate() {
        // PostgreSQL concatenates 'a' 'b' only when a line break separates them
        assertEquals("SELECT 'a'\n    'b'\nFROM t;", fmt(Dialect.POSTGRESQL, "select 'a'\n'b' from t;"));
    }

    @Test
    void emptyAndCommentOnlyInput() {
        assertEquals("", Formatter.format(Dialect.ORACLE, "").text());
        assertFalse(Formatter.format(Dialect.ORACLE, "-- only a comment\n").changed());
    }

    @Test
    void optionsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new FormatOptions(-1, null, 100, null));
        assertThrows(IllegalArgumentException.class, () -> new FormatOptions(4, null, 5, null));
        FormatOptions o = new FormatOptions(4, null, 100, null);
        assertEquals(FormatOptions.KeywordCase.UPPER, o.keywordCase());
        assertEquals(FormatOptions.CommaStyle.TRAILING, o.commaStyle());
        assertEquals(120, o.withMaxLineWidth(120).maxLineWidth());
    }
}
