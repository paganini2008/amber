package com.github.amber.rewrite;

import com.github.amber.core.i18n.Messages;
import com.github.amber.core.Dialect;
import com.github.amber.core.ir.StatementKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AMB-S010: {@code SELECT *} in a statement that writes a table or view (INSERT … SELECT, CREATE TABLE
 * AS, CREATE VIEW, SELECT INTO) becomes an explicit column list when the columns of every source table
 * are known: from imported metadata, or from a CREATE TABLE earlier in the script (a table created or
 * altered more than once is skipped). Several sources are qualified with their alias. NATURAL joins
 * and USING merge columns and are left alone.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class SelectStarRule implements RewriteRule {

    private static final Set<StatementKind> WRITES = Set.of(StatementKind.INSERT, StatementKind.CREATE_TABLE_AS,
            StatementKind.CREATE_VIEW, StatementKind.CREATE_MATERIALIZED_VIEW, StatementKind.SELECT_INTO);
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*");
    private static final Set<String> RESERVED = Set.of("ORDER", "GROUP", "SELECT", "FROM", "WHERE", "TABLE", "USER",
            "LEVEL", "DATE", "COMMENT", "CHECK", "INDEX", "NUMBER", "ROWNUM", "UID", "ACCESS", "FILE", "MODE", "SESSION",
            "ROW", "ROWS", "START", "CONNECT", "DESC", "ASC", "BY", "AND", "OR", "NOT", "NULL", "IN", "IS", "AS", "ON",
            "TO", "WITH", "UNION", "ALL", "DISTINCT", "CASE", "WHEN", "THEN", "ELSE", "END", "KEY", "PRIMARY", "DEFAULT",
            "VALUES", "SET", "INTO", "UPDATE", "DELETE", "INSERT", "CREATE", "DROP", "ALTER", "GRANT", "CURRENT",
            "TIMESTAMP", "TIME", "LIMIT", "OFFSET", "FETCH", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "FULL", "CROSS");
    private static final int WRAP = 100;

    @Override
    public String id() {
        return "AMB-S010";
    }

    @Override
    public List<Candidate> propose(RewriteContext ctx) {
        List<Candidate> out = new ArrayList<>();
        if (!WRITES.contains(ctx.statement().kind())) {
            return out;
        }
        Sql sql = ctx.sql();
        for (Block b : sql.blocks()) {
            if (b.listEnd - b.listStart != 1 || !sql.sym(b.listStart, '*') || b.from < 0) {
                continue;
            }
            List<Source> sources = b.sources();
            if (sources.isEmpty()) {
                continue;
            }
            List<String> names = new ArrayList<>();
            List<String> origins = new ArrayList<>();
            boolean ok = true;
            for (Source s : sources) {
                Optional<List<String>> cols = ctx.columns(s);
                if (cols.isEmpty() || s.join().contains("NATURAL")
                        || (s.onStart() >= 0 && sql.word(s.onStart(), "USING"))) {
                    ok = false;
                    break;
                }
                String prefix = sources.size() == 1 ? ""
                        : (s.alias() != null ? s.alias() : sql.tok(s.nameEnd()).text()) + ".";
                for (String c : cols.get()) {
                    names.add(prefix + quote(ctx, s, c));
                }
                origins.add(Messages.t(ctx.catalogNamesExact(s) ? "rewrite.selectStar.origin.metadata"
                        : "rewrite.selectStar.origin.script", s.name()));
            }
            if (!ok || names.isEmpty()) {
                continue;
            }
            String list = layout(sql, b.listStart, names);
            out.add(new Candidate(id(), Messages.t("rewrite.selectStar.title"),
                    Messages.t("rewrite.selectStar.explanation"),
                    List.of(ctx.replace(b.listStart, b.listStart, list)), Safety.EQUIVALENT_WITH_METADATA,
                    Messages.t("rewrite.selectStar.safety", String.join(", ", origins))));
        }
        return out;
    }

    /** Joins the names; long lists wrap with the continuation aligned under the first name. */
    private static String layout(Sql sql, int star, List<String> names) {
        String one = String.join(", ", names);
        int offset = sql.tok(star).start();
        int lineStart = offset;
        while (lineStart > 0 && sql.text.charAt(lineStart - 1) != '\n' && sql.text.charAt(lineStart - 1) != '\r') {
            lineStart--;
        }
        int column = offset - lineStart;
        if (column + one.length() <= WRAP) {
            return one;
        }
        String indent = " ".repeat(column);
        StringBuilder sb = new StringBuilder();
        int width = column;
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i) + (i + 1 < names.size() ? "," : "");
            if (i > 0) {
                if (width + 1 + n.length() > WRAP) {
                    sb.append('\n').append(indent);
                    width = column;
                } else {
                    sb.append(' ');
                    width++;
                }
            }
            sb.append(n);
            width += n.length();
        }
        return sb.toString();
    }

    /** A column name written so the database resolves it to the same column. */
    static String quote(RewriteContext ctx, Source s, String name) {
        Dialect d = ctx.dialect();
        String open = d == Dialect.SQLSERVER ? "[" : (d == Dialect.MYSQL || d == Dialect.MARIADB) ? "`" : "\"";
        String close = d == Dialect.SQLSERVER ? "]" : open;
        String text = ctx.source().text();
        if (text.contains(open + name + close)) {
            return open + name + close;            // the script itself quotes it
        }
        boolean plain = PLAIN.matcher(name).matches() && !RESERVED.contains(name.toUpperCase(Locale.ROOT));
        if (ctx.catalogNamesExact(s)) {
            boolean folds = switch (d.nameCase()) {
                case UPPER -> name.equals(name.toUpperCase(Locale.ROOT));
                case LOWER -> name.equals(name.toLowerCase(Locale.ROOT));
                case INSENSITIVE -> true;
            };
            return plain && folds ? name : open + name + close;
        }
        if (plain) {
            return name;
        }
        String folded = switch (d.nameCase()) {
            case UPPER -> name.toUpperCase(Locale.ROOT);
            case LOWER -> name.toLowerCase(Locale.ROOT);
            case INSENSITIVE -> name;
        };
        return open + folded + close;
    }
}
