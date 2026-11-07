package com.github.amber.format;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Words whose case the formatter may change. The list is deliberately conservative: it holds
 * reserved words and procedural keywords, and leaves out words that legacy schemas commonly use as
 * table or column names ({@code name}, {@code type}, {@code date}, {@code level}, {@code value},
 * {@code count} …). Changing the case of an unquoted identifier is harmless in most databases but not
 * in all (MySQL table names on Linux, SQL Server with a case-sensitive collation), so anything that
 * might be an identifier keeps its case.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class Keywords {

    private static final Set<String> CASEABLE = new HashSet<>();

    static {
        String words = """
                ADD ALL ALTER AND ANY APPLY AS ASC AUTHID AUTO_INCREMENT AUTONOMOUS_TRANSACTION
                BEFORE AFTER BEGIN BETWEEN BIGINT BINARY_INTEGER BLOB BODY BOOLEAN BULK BY
                CASCADE CASE CAST CATCH CHAR CHECK CLOB CLOSE CLUSTERED COALESCE COLLATE COLLECT COLUMN COMMIT
                CONFLICT CONNECT CONSTANT CONSTRAINT CONTINUE CREATE CROSS CUBE CURRENT_DATE CURRENT_TIME
                CURRENT_TIMESTAMP CURRENT_USER CURSOR
                DEALLOCATE DECIMAL DECLARE DEFAULT DEFERRABLE DEFERRED DELETE DESC DETERMINISTIC DISTINCT
                DISTINCTROW DO DROP DUPLICATE
                EACH ELSE ELSEIF ELSIF END ESCAPE EXCEPT EXCEPTION EXEC EXECUTE EXISTS EXIT
                FALSE FETCH FOR FORALL FOREIGN FROM FULL FUNCTION
                GOTO GRANT GROUP GROUPING
                HANDLER HAVING
                IDENTITY IF ILIKE IMMEDIATE IN INDEX INITIALLY INNER INSERT INSTEAD INT INTEGER INTERSECT INTERVAL
                INOUT INTO IS ISOPEN ITERATE
                JOIN
                KEY
                LATERAL LEAVE LEFT LIKE LIMIT LOOP
                MATCHED MERGE MINUS
                NATURAL NCHAR NOCOPY NOCOUNT NOCYCLE NONCLUSTERED NOT NOTFOUND NOWAIT NULL NULLIF NULLS NUMBER
                NUMERIC NVARCHAR NVARCHAR2
                OF OFFSET ON ONLY OPEN OR ORDER OTHERS OUT OUTER OUTPUT OVER
                PACKAGE PARTITION PIPELINED PIVOT PRAGMA PRIMARY PRINT PRIOR PROC PROCEDURE
                QUALIFY
                RAISE RAISERROR RECURSIVE REFERENCES REGEXP RELEASE REPEAT REPLACE RESIGNAL RESTRICT RETURN
                RETURNING RETURNS REVOKE RIGHT RLIKE ROLLBACK ROLLUP ROW ROWS ROWTYPE
                SAVEPOINT SELECT SET SETS SIBLINGS SIGNAL SMALLINT SOME SQLEXCEPTION SQLWARNING
                TABLE THEN THROW TINYINT TO TOP TRAN TRANSACTION TRIGGER TRUE TRUNCATE TRY
                UNION UNIQUE UNPIVOT UNSIGNED UNTIL UPDATE USING
                VALUES VARCHAR VARCHAR2 VIEW
                WHEN WHERE WHILE WINDOW WITH WITHIN
                """;
        for (String w : words.split("\\s+")) {
            if (!w.isBlank()) {
                CASEABLE.add(w);
            }
        }
    }

    private Keywords() {
    }

    static boolean isCaseable(String upperWord) {
        return CASEABLE.contains(upperWord);
    }

    /**
     * The text to print for word token {@code t}; {@code prev} and {@code next} are the neighbouring
     * tokens (or null). Words glued to '.', '@', ':' or '&' are names or variables and keep their case.
     */
    static String apply(FormatToken t, FormatToken prev, FormatToken next, FormatOptions.KeywordCase kc) {
        if (kc == FormatOptions.KeywordCase.PRESERVE || t.kind != FormatToken.Kind.WORD || !CASEABLE.contains(t.upper)) {
            return t.text;
        }
        if (prev != null && !t.spaceBefore && prev.kind == FormatToken.Kind.SYMBOL
                && (prev.text.equals(".") || prev.text.equals("@") || prev.text.equals(":") || prev.text.equals("&")
                || prev.text.equals("$"))) {
            return t.text;
        }
        if (next != null && !next.spaceBefore && next.isSymbol(".")) {
            return t.text;
        }
        return kc == FormatOptions.KeywordCase.UPPER ? t.upper : t.text.toLowerCase(Locale.ROOT);
    }
}
