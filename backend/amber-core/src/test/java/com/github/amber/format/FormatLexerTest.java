package com.github.amber.format;

import com.github.amber.core.Dialect;
import com.github.amber.format.FormatToken.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dialect rules of the comment-preserving {@link FormatLexer} and the {@link SafetyCheck} built on it.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class FormatLexerTest {

    private static List<String> texts(Dialect d, String sql) {
        return FormatLexer.lex(sql, d, null).stream().map(t -> t.kind + ":" + t.text).toList();
    }

    @Test
    void commentsAreTokensWithTheirWhitespaceRecorded() {
        List<FormatToken> t = FormatLexer.lex("a -- x\n\n  /* y */b", Dialect.ORACLE, null);
        assertEquals(List.of(Kind.WORD, Kind.LINE_COMMENT, Kind.BLOCK_COMMENT, Kind.WORD), t.stream().map(x -> x.kind).toList());
        assertEquals("-- x", t.get(1).text);
        assertEquals(2, t.get(2).newlinesBefore);
        assertFalse(t.get(3).spaceBefore);
        assertTrue(t.get(2).isComment());
        assertEquals("BLOCK_COMMENT(/* y */)", t.get(2).toString());
    }

    @Test
    void oracleForms() {
        assertEquals(List.of("STRING:q'[it's]'", "WORD:&&var.", "SYMBOL:=>", "WORD:x$y#z", "NUMBER:1", "SYMBOL:..",
                "NUMBER:10", "SYMBOL:<<", "WORD:l", "SYMBOL:>>"), texts(Dialect.ORACLE, "q'[it's]' &&var. => x$y#z 1..10 <<l>>"));
        assertEquals(List.of("SYMBOL:&", "SYMBOL:&"), texts(Dialect.ORACLE, "& &"));
        assertEquals(List.of("STRING:q'\uD83D\uDE00a'b\uD83D\uDE00'", "WORD:x"), texts(Dialect.ORACLE, "q'\uD83D\uDE00a'b\uD83D\uDE00' x"));
        assertEquals(Kind.UNTERMINATED, FormatLexer.lex("q'[never", Dialect.ORACLE, null).getFirst().kind);
        assertEquals(Kind.UNTERMINATED, FormatLexer.lex("q'", Dialect.ORACLE, null).getFirst().kind);
    }

    @Test
    void sqlServerForms() {
        assertEquals(List.of("QUOTED:[a]]b]", "WORD:@@ROWCOUNT", "WORD:#tmp", "SYMBOL:*=", "BLOCK_COMMENT:/* a /* b */ c */",
                "WORD:$action", "SYMBOL:$", "SYMBOL:("), texts(Dialect.SQLSERVER, "[a]]b] @@ROWCOUNT #tmp *= /* a /* b */ c */ $action $("));
    }

    @Test
    void postgresForms() {
        assertEquals(List.of("STRING:$f$ x $ y $f$", "WORD:$1", "STRING:E'a\\'b'", "SYMBOL:::", "SYMBOL:@>", "SYMBOL:=",
                "SYMBOL:-", "NUMBER:1", "SYMBOL:#>>", "SYMBOL:..", "WORD:json", "SYMBOL:#>"),
                texts(Dialect.POSTGRESQL, "$f$ x $ y $f$ $1 E'a\\'b' :: @> =-1 #>> .. json#>"));
        assertEquals(Kind.UNTERMINATED, FormatLexer.lex("$$ open", Dialect.POSTGRESQL, null).getFirst().kind);
        assertEquals(List.of("SYMBOL:*", "BLOCK_COMMENT:/* c */"), texts(Dialect.POSTGRESQL, "*/* c */"));
    }

    @Test
    void mysqlForms() {
        assertEquals(List.of("NUMBER:5", "SYMBOL:-", "SYMBOL:-", "NUMBER:1", "LINE_COMMENT:# c"), texts(Dialect.MYSQL, "5--1 # c"));
        assertEquals(List.of("WORD:@v", "WORD:@@global", "SYMBOL:.", "WORD:x", "WORD:@'q v'", "QUOTED:`a``b`", "STRING:'a\\'b'",
                "BLOCK_COMMENT:/*!40101 SET x */", "SYMBOL:->>", "LINE_COMMENT:-- c"),
                texts(Dialect.MARIADB, "@v @@global.x @'q v' `a``b` 'a\\'b' /*!40101 SET x */ ->> -- c"));
        assertEquals(List.of("SYMBOL:@", "SYMBOL:="), texts(Dialect.MYSQL, "@ ="));
    }

    @Test
    void h2AndSqliteForms() {
        assertEquals(List.of("WORD:a", "LINE_COMMENT:// c"), texts(Dialect.H2, "a // c"));
        assertEquals(List.of("QUOTED:[x]", "QUOTED:`y`"), texts(Dialect.SQLITE, "[x] `y`"));
        assertEquals(List.of("NUMBER:1.5e-3", "NUMBER:.5", "NUMBER:0x1F"), texts(Dialect.DB2, "1.5e-3 .5 0x1F"));
    }

    @Test
    void terminatorIsOneToken() {
        List<FormatToken> t = FormatLexer.lex("END\n$$", Dialect.MYSQL, "$$");
        assertEquals(Kind.TERMINATOR, t.getLast().kind);
        assertEquals(1, t.getLast().newlinesBefore);
        assertTrue(FormatLexer.hasUnterminated(FormatLexer.lex("'open", Dialect.MYSQL, null)));
        assertTrue(FormatLexer.hasUnterminated(FormatLexer.lex("/* open", Dialect.MYSQL, null)));
        assertTrue(FormatLexer.hasUnterminated(FormatLexer.lex("@'open", Dialect.MYSQL, null)));
    }

    @Test
    void safetyCheckAcceptsWhitespaceAndKeywordCaseOnly() {
        assertEquals(null, SafetyCheck.verify(Dialect.ORACLE, "select a from t", "SELECT a\nFROM t", null));
        assertTrue(SafetyCheck.verify(Dialect.ORACLE, "select a from t", "select A from t", null).contains("'a' to 'A'"));
        assertTrue(SafetyCheck.verify(Dialect.ORACLE, "select a -- x\nfrom t", "select a -- x from t", null).contains("'-- x from t'"));
        assertTrue(SafetyCheck.verify(Dialect.ORACLE, "select a from t", "select a from t x", null).contains("count"));
        assertTrue(SafetyCheck.verify(Dialect.ORACLE, "select 'a' from t", "select a from t", null).contains("STRING"));
        assertTrue(SafetyCheck.verify(Dialect.ORACLE, "select a\nfrom t", "select a\n\nfrom t", null).contains("blank"));
        assertTrue(SafetyCheck.verify(Dialect.ORACLE, "select a / b from t", "select a\n/\nb from t", null).contains("SQL*Plus"));
        assertTrue(SafetyCheck.verify(Dialect.POSTGRESQL, "select '" + "x".repeat(60) + "'", "select '" + "y".repeat(60) + "'", null)
                .contains("..."));
    }

    @Test
    void keywordCasingSkipsNamesAndVariables() {
        List<FormatToken> t = FormatLexer.lex("x.select select. :select", Dialect.ORACLE, null);
        assertEquals("select", Keywords.apply(t.get(2), t.get(1), t.get(3), FormatOptions.KeywordCase.UPPER));
        assertEquals("select", Keywords.apply(t.get(3), t.get(2), t.get(4), FormatOptions.KeywordCase.UPPER));
        assertEquals("select", Keywords.apply(t.get(6), t.get(5), null, FormatOptions.KeywordCase.UPPER));
        assertTrue(Keywords.isCaseable("SELECT"));
        assertFalse(Keywords.isCaseable("NAME"));
    }
}
