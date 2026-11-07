package com.github.amber.dialect.ir;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.Confidence;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.ObjectName;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.core.source.SourceText;
import com.github.amber.core.source.TextRange;
import com.github.amber.dialect.split.ScanToken;
import com.github.amber.dialect.split.ScannerOptions;
import com.github.amber.dialect.split.SqlScanner;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Token-level reading of a statement that did not parse (fallback mode only). It recognizes the
 * keyword patterns that name objects — {@code INSERT INTO t}, {@code UPDATE t}, {@code DELETE FROM t},
 * {@code MERGE INTO t … USING s}, {@code CREATE [TEMP] TABLE t}, {@code DROP/TRUNCATE/ALTER TABLE t},
 * {@code FROM t}, {@code JOIN t} — so the statement still appears in the outline and data flow.
 * Every result is {@link Confidence#PARTIAL}.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class FallbackScanner {

    private static final Set<String> TEMP_WORDS = Set.of("TEMP", "TEMPORARY");

    public record Result(StatementKind kind, List<ObjectRef> refs) {
    }

    private FallbackScanner() {
    }

    public static Result scan(Dialect dialect, SourceText source, TextRange range) {
        String text = source.slice(range);
        int base = range.start().offset();
        SqlScanner sc = new SqlScanner(text, ScannerOptions.forDialect(dialect));
        List<ScanToken> tokens = new ArrayList<>();
        ScanToken t;
        while ((t = sc.next()) != null) {
            tokens.add(t);
        }
        List<ObjectRef> refs = new ArrayList<>();
        StatementKind kind = StatementKind.UNPARSED;
        boolean temp = false;
        for (int i = 0; i < tokens.size(); i++) {
            ScanToken tok = tokens.get(i);
            if (tok.isWordIn(TEMP_WORDS)) {
                temp = true;
            }
            if (tok.isWord("INSERT") || tok.isWord("MERGE")) {
                int j = skip(tokens, i + 1, "INTO");
                kind = kindOnce(kind, tok.isWord("INSERT") ? StatementKind.INSERT : StatementKind.MERGE);
                add(refs, dialect, source, base, tokens, j, tok.isWord("INSERT") ? Access.INSERT : Access.MERGE, ObjectKind.TABLE);
            } else if (tok.isWord("UPDATE") && i == 0) {
                kind = kindOnce(kind, StatementKind.UPDATE);
                add(refs, dialect, source, base, tokens, i + 1, Access.UPDATE, ObjectKind.TABLE);
            } else if (tok.isWord("DELETE")) {
                kind = kindOnce(kind, StatementKind.DELETE);
                add(refs, dialect, source, base, tokens, skip(tokens, i + 1, "FROM"), Access.DELETE, ObjectKind.TABLE);
            } else if (tok.isWord("TABLE") && i > 0) {
                ScanToken verb = tokens.getFirst();
                int j = skipIfNotExists(tokens, i + 1);
                if (verb.isWord("CREATE")) {
                    kind = kindOnce(kind, StatementKind.CREATE_TABLE);
                    add(refs, dialect, source, base, tokens, j, Access.CREATE, temp ? ObjectKind.TEMP_TABLE : ObjectKind.TABLE);
                } else if (verb.isWord("DROP")) {
                    kind = kindOnce(kind, StatementKind.DROP);
                    add(refs, dialect, source, base, tokens, j, Access.DROP, ObjectKind.TABLE);
                } else if (verb.isWord("TRUNCATE")) {
                    kind = kindOnce(kind, StatementKind.TRUNCATE);
                    add(refs, dialect, source, base, tokens, j, Access.TRUNCATE, ObjectKind.TABLE);
                } else if (verb.isWord("ALTER")) {
                    kind = kindOnce(kind, StatementKind.ALTER);
                    add(refs, dialect, source, base, tokens, j, Access.ALTER, ObjectKind.TABLE);
                }
            } else if (tok.isWord("FROM") || tok.isWord("JOIN") || tok.isWord("USING")) {
                if (i == 0 || !tokens.get(i - 1).isWord("DELETE")) {
                    add(refs, dialect, source, base, tokens, i + 1, Access.READ, ObjectKind.TABLE);
                }
            } else if (tok.isWord("SELECT") && kind == StatementKind.UNPARSED && i == 0) {
                kind = StatementKind.SELECT;
            }
        }
        return new Result(kind, refs);
    }

    private static StatementKind kindOnce(StatementKind current, StatementKind candidate) {
        return current == StatementKind.UNPARSED || current == StatementKind.SELECT ? candidate : current;
    }

    private static int skip(List<ScanToken> tokens, int i, String word) {
        return i < tokens.size() && tokens.get(i).isWord(word) ? i + 1 : i;
    }

    private static int skipIfNotExists(List<ScanToken> tokens, int i) {
        if (i + 1 < tokens.size() && tokens.get(i).isWord("IF")) {
            int j = i + 1;
            if (tokens.get(j).isWord("NOT")) {
                j++;
            }
            return skip(tokens, j, "EXISTS");
        }
        return i;
    }

    /** Reads {@code part(.part)*} starting at token {@code i}; ignores subqueries and keywords. */
    private static void add(List<ObjectRef> refs, Dialect dialect, SourceText source, int base, List<ScanToken> tokens,
                            int i, Access access, ObjectKind kind) {
        if (i >= tokens.size()) {
            return;
        }
        ScanToken first = tokens.get(i);
        if (first.type() != ScanToken.Type.WORD && first.type() != ScanToken.Type.QUOTED_IDENTIFIER) {
            return;
        }
        if (first.isWord("SELECT") || first.isWord("LATERAL") || first.isWord("TABLE")) {
            return;
        }
        int end = first.end();
        int j = i + 1;
        while (j + 1 < tokens.size() && tokens.get(j).isSymbol('.')
                && (tokens.get(j + 1).type() == ScanToken.Type.WORD || tokens.get(j + 1).type() == ScanToken.Type.QUOTED_IDENTIFIER)) {
            end = tokens.get(j + 1).end();
            j += 2;
        }
        TextRange range = source.range(base + first.start(), base + end);
        ObjectName name = ObjectName.parse(dialect, source.slice(range));
        if (name != null) {
            refs.add(new ObjectRef(name, kind, access, range, Confidence.PARTIAL));
        }
    }
}
