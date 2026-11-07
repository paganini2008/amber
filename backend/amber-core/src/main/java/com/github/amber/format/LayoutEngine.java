package com.github.amber.format;

import com.github.amber.core.Dialect;
import com.github.amber.format.FormatToken.Kind;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * Lays out the tokens of one statement unit. It is a token-stream formatter, not a parser: it reads
 * clause keywords, parentheses, CASE … END and procedural block keywords and decides line breaks and
 * indentation from them, so it copes with any input, including SQL the grammars reject.
 *
 * <p>Only whitespace between tokens (and the case of reserved words) is decided here; every token is
 * printed exactly once and in order. Three rules keep that whitespace harmless:
 * <ul>
 *   <li>tokens that touch in the input (no whitespace between them) stay touching unless the pair is
 *       known to be separable, so forms such as {@code N'x'}, {@code :bind}, {@code tbl@link},
 *       {@code x%TYPE} or {@code $(var)} keep their exact shape;</li>
 *   <li>after a line comment the next token always starts a new line;</li>
 *   <li>blank lines are only kept, never added.</li>
 * </ul>
 * Layout decisions depend on the token sequence and on those few whitespace facts only, which makes
 * the output a fixed point: formatting it again yields the same text.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class LayoutEngine {

    enum FrameKind { QUERY, INSERT, UPDATE, DELETE, MERGE, CREATE_TABLE, VIEW, TRIGGER, HEADER, TSQL_HEADER, GENERIC, COND, LIST, CASE }

    private enum Stop { END, SEMI, BOUNDARY, SECTION, COND_END }

    private enum Body { LIST, COND, JOIN, SETOP, CTE, PLAIN }

    private record Clause(String key, int length, Body body, int threshold, boolean inline, int extra) {

        Clause(String key, int length, Body body, int threshold, boolean inline) {
            this(key, length, body, threshold, inline, 0);
        }
    }

    /** Layout state of one statement, subquery, parenthesized list or CASE expression. */
    private static final class Frame {
        FrameKind kind;
        int indent;
        final int baseIndent;
        int start;
        int limit = -1;
        String firstWord;
        Set<String> condStops;
        String clause;
        boolean listBroken;
        int itemIndent;
        boolean fill;
        boolean andOr;
        int andOrIndent;
        int between;
        boolean joinOn;
        boolean cteList;
        boolean cteMainPending;
        boolean fromInsert;
        boolean inAction;
        boolean tableParenDone;
        boolean paramsDone;
        boolean sectionSeen;
        boolean sameLine;

        Frame(FrameKind kind, int indent) {
            this.kind = kind;
            this.indent = indent;
            this.baseIndent = indent;
        }
    }

    private static final int MANY = Integer.MAX_VALUE;

    private static final Set<String> TSQL_STARTERS = Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "DECLARE",
            "SET", "IF", "ELSE", "WHILE", "BEGIN", "END", "RETURN", "PRINT", "EXEC", "EXECUTE", "RAISERROR", "THROW",
            "CREATE", "ALTER", "DROP", "TRUNCATE", "GOTO", "BREAK", "CONTINUE", "OPEN", "CLOSE", "FETCH", "DEALLOCATE",
            "COMMIT", "ROLLBACK", "SAVE", "WAITFOR", "USE", "GRANT", "REVOKE", "DENY", "DBCC", "BULK", "BACKUP",
            "RESTORE", "CHECKPOINT", "KILL", "RECONFIGURE", "SHUTDOWN", "ENABLE", "DISABLE", "UPDATETEXT", "WRITETEXT",
            "READTEXT");
    /** Words before a DML keyword that make it part of the current T-SQL statement. */
    private static final Set<String> TSQL_DML_CONTINUATION = Set.of("UNION", "ALL", "EXCEPT", "INTERSECT", "AS", "FOR",
            "AFTER", "OF", "INSTEAD", "THEN", "IF", "WHILE", "AND", "OR", "NOT", "GRANT", "REVOKE", "DENY", "WITH");
    private static final Set<String> TSQL_DML = Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "SET", "EXEC",
            "EXECUTE");
    private static final Set<String> OBJECT_WORDS = Set.of("TABLE", "VIEW", "PROCEDURE", "PROC", "FUNCTION", "INDEX",
            "TRIGGER", "SCHEMA", "DATABASE", "TYPE", "SEQUENCE", "SYNONYM", "USER", "ROLE", "LOGIN", "COLUMN",
            "CONSTRAINT", "DEFAULT", "RULE", "STATISTICS", "ASSEMBLY", "EXISTS");
    private static final Set<String> TX_WORDS = Set.of("TRAN", "TRANSACTION", "WORK", "DISTRIBUTED", "ISOLATION",
            "DEFERRED", "IMMEDIATE", "EXCLUSIVE", "READ");
    private static final Set<String> END_FOLLOWERS = Set.of("IF", "LOOP", "CASE", "WHILE", "REPEAT", "FOR", "TRY", "CATCH");
    private static final Set<String> CREATE_MODIFIERS = Set.of("OR", "REPLACE", "EDITIONABLE", "NONEDITIONABLE",
            "EDITIONING", "NONEDITIONING", "FORCE", "NOFORCE", "ALTER");
    private static final Set<String> MEMBER_MODIFIERS = Set.of("MEMBER", "STATIC", "CONSTRUCTOR", "MAP", "ORDER",
            "OVERRIDING", "FINAL", "INSTANTIABLE", "NOT");
    private static final Set<String> NO_QUERY_SWITCH = Set.of("GRANT", "REVOKE", "DENY", "AUDIT", "NOAUDIT", "COMMENT");
    private static final Set<String> SELECT_MODIFIERS = Set.of("DISTINCT", "ALL", "UNIQUE", "DISTINCTROW",
            "SQL_CALC_FOUND_ROWS", "SQL_NO_CACHE", "SQL_CACHE", "HIGH_PRIORITY", "STRAIGHT_JOIN", "SQL_SMALL_RESULT",
            "SQL_BIG_RESULT", "SQL_BUFFER_RESULT");
    private static final Set<String> SET_OPERATORS = Set.of("UNION", "INTERSECT", "EXCEPT", "MINUS");
    private static final Set<String> BIN_OPS = Set.of("=", "<>", "!=", "<=", ">=", ":=", "=>", "||", "<", ">");
    private static final Set<String> KW_SPACE_PAREN = Set.of("IN", "AS", "ON", "USING", "EXISTS", "AND", "OR", "NOT",
            "WHERE", "FROM", "JOIN", "SELECT", "UNION", "ALL", "ANY", "SOME", "THEN", "ELSE", "WHEN", "RETURN", "BY",
            "OVER", "WITH");

    private static final Set<String> STOP_END = Set.of("END");
    private static final Set<String> STOP_END_EXCEPTION = Set.of("END", "EXCEPTION");
    private static final Set<String> STOP_BEGIN = Set.of("BEGIN");
    private static final Set<String> STOP_BEGIN_END = Set.of("BEGIN", "END");
    private static final Set<String> STOP_THEN = Set.of("THEN");
    private static final Set<String> STOP_WHEN_END = Set.of("WHEN", "END");
    private static final Set<String> STOP_WHEN_ELSE_END = Set.of("WHEN", "ELSE", "END");
    private static final Set<String> STOP_IF_BRANCH = Set.of("ELSIF", "ELSEIF", "ELSE", "END");
    private static final Set<String> STOP_LOOP = Set.of("LOOP", "DO");
    private static final Set<String> STOP_UNTIL = Set.of("UNTIL");

    private final List<FormatToken> all;
    private final Dialect dialect;
    private final FormatOptions options;
    private final boolean tsql;
    private final boolean oracle;
    private final int unit;
    private final int maxWidth;
    private final LineWriter w;

    private final int[] sig;
    private final int n;
    private final int[] match;
    private final int[] caseEnd;
    private final boolean[] caseEndToken;
    private final boolean[] caseStatement;
    private final boolean[] glued;
    private final int[] space;
    private final boolean[] forced;
    private final long[] widthPrefix;
    private final int[] forcedPrefix;
    private final int[] multiPrefix;

    private int i;
    private int pendingIndent = -1;
    private int anchor;
    private int procDepth;

    LayoutEngine(List<FormatToken> tokens, Dialect dialect, FormatOptions options, String lineSeparator,
                 String prefix, int startColumn) {
        this.all = tokens;
        this.dialect = dialect;
        this.options = options;
        this.tsql = dialect == Dialect.SQLSERVER;
        this.oracle = dialect == Dialect.ORACLE;
        this.unit = options.indentSize();
        this.maxWidth = Math.max(20, options.maxLineWidth() - prefix.length());
        this.w = new LineWriter(lineSeparator, prefix, startColumn);

        int count = 0;
        for (FormatToken t : tokens) {
            if (!t.isComment()) {
                count++;
            }
        }
        sig = new int[count];
        int s = 0;
        for (int j = 0; j < tokens.size(); j++) {
            if (!tokens.get(j).isComment()) {
                sig[s++] = j;
            }
        }
        n = count;

        int m = tokens.size();
        space = new int[m];
        forced = new boolean[m];
        widthPrefix = new long[m + 1];
        forcedPrefix = new int[m + 1];
        multiPrefix = new int[m + 1];
        for (int j = 0; j < m; j++) {
            FormatToken t = tokens.get(j);
            FormatToken p = j > 0 ? tokens.get(j - 1) : null;
            space[j] = p != null && wantSpace(p, t) ? 1 : 0;
            forced[j] = p != null && (p.kind == Kind.LINE_COMMENT
                    || (t.isComment() && t.newlinesBefore > 0)
                    || (p.kind == Kind.BLOCK_COMMENT && t.newlinesBefore > 0)
                    || (p.kind == Kind.STRING && t.kind == Kind.STRING && t.newlinesBefore > 0));
            widthPrefix[j + 1] = widthPrefix[j] + space[j] + t.text.length();
            forcedPrefix[j + 1] = forcedPrefix[j] + (forced[j] ? 1 : 0);
            multiPrefix[j + 1] = multiPrefix[j] + (t.isMultiLine() ? 1 : 0);
        }

        glued = new boolean[n];
        for (int k = 0; k < n; k++) {
            FormatToken t = tok(k);
            glued[k] = t.kind == Kind.WORD && ((k > 0 && !t.spaceBefore && tok(k - 1).isSymbol("."))
                    || (k + 1 < n && !tok(k + 1).spaceBefore && tok(k + 1).isSymbol(".")));
        }
        match = new int[n];
        caseEnd = new int[n];
        caseEndToken = new boolean[n];
        caseStatement = new boolean[n];
        Arrays.fill(match, -1);
        Arrays.fill(caseEnd, -1);
        matchGroups();
    }

    String layout() {
        while (i < n) {
            int before = i;
            statement(0, false);
            if (i == before) {
                emit(i, true, 0);
                i++;
            }
        }
        int from = n == 0 ? 0 : sig[n - 1] + 1;
        for (int c = from; c < all.size(); c++) {
            emitComment(c, contIndent());
        }
        return w.toString();
    }

    // ----- pre-pass: parentheses, CASE … END -------------------------------------------------

    private void matchGroups() {
        Deque<Integer> parens = new ArrayDeque<>();
        Deque<int[]> blocks = new ArrayDeque<>();            // {type 0=CASE 1=BLOCK, sig index, enclosing paren}
        boolean[] endCase = new boolean[n];
        for (int k = 0; k < n; k++) {
            FormatToken t = tok(k);
            int enclosing = parens.isEmpty() ? -1 : parens.peek();
            if (t.isSymbol("(")) {
                parens.push(k);
            } else if (t.isSymbol(")")) {
                if (!parens.isEmpty()) {
                    int open = parens.pop();
                    match[open] = k;
                    match[k] = open;
                    while (!blocks.isEmpty() && blocks.peek()[2] == open) {
                        blocks.pop();
                    }
                }
            } else if (t.kind == Kind.WORD && !glued[k]) {
                String wd = t.upper;
                if (wd.equals("CASE") && !endCase[k]) {
                    blocks.push(new int[]{0, k, enclosing});
                } else if (wd.equals("BEGIN") && isBlockBegin(k)) {
                    blocks.push(new int[]{1, k, enclosing});
                } else if (wd.equals("END")) {
                    String next = word(k + 1);
                    if (next != null && Set.of("IF", "LOOP", "WHILE", "REPEAT", "FOR").contains(next)) {
                        continue;
                    }
                    int[] top = blocks.peek();
                    if (top == null || top[2] != enclosing) {
                        continue;
                    }
                    if ("CASE".equals(next)) {
                        if (top[0] == 0) {
                            blocks.pop();
                            caseEnd[top[1]] = k;
                            caseEndToken[k] = true;
                            caseStatement[top[1]] = true;
                            endCase[k + 1] = true;
                        }
                    } else if ("TRY".equals(next) || "CATCH".equals(next)) {
                        if (top[0] == 1) {
                            blocks.pop();
                        }
                    } else {
                        blocks.pop();
                        if (top[0] == 0) {
                            caseEnd[top[1]] = k;
                            caseEndToken[k] = true;
                        }
                    }
                }
            }
        }
    }

    // ----- statements and procedural blocks ------------------------------------------------

    private void statementList(int depth, Set<String> stops) {
        while (i < n) {
            String wd = word(i);
            if (wd != null && !glued[i] && stops.contains(wd)) {
                return;
            }
            int before = i;
            statement(depth, false);
            if (i == before) {
                emit(i, true, indent(depth));
                i++;
            }
        }
    }

    private void statement(int depth, boolean sameLine) {
        FormatToken t = tok(i);
        int ind = indent(depth);
        if (t.kind == Kind.TERMINATOR) {
            emitTerminator(i);
            i++;
            return;
        }
        if (t.isSymbol(";")) {
            boolean glue = i + 1 < n && !tok(i + 1).spaceBefore && tok(i + 1).kind == Kind.WORD && sig[i + 1] == sig[i] + 1;
            emit(i, glue && !sameLine, ind);
            i++;
            if (glue) {
                statement(depth, true);              // ;WITH cte AS (...)
            }
            return;
        }
        if (t.isSymbol("<<") && !tsql) {
            emit(i, !sameLine, ind);
            i++;
            for (int guard = 0; i < n && guard < 4; guard++) {
                boolean close = tok(i).isSymbol(">>");
                emit(i, false, ind);
                i++;
                if (close) {
                    break;
                }
            }
            return;
        }
        String wd = glued[i] ? null : word(i);
        if (wd != null && i + 1 < n && tok(i + 1).isSymbol(":") && !Keywords.isCaseable(wd)
                && !TSQL_STARTERS.contains(wd)) {
            emit(i, !sameLine, ind);
            emit(i + 1, false, ind);
            i += 2;
            String next = word(i);
            if (next != null && Set.of("BEGIN", "LOOP", "WHILE", "REPEAT", "FOR").contains(next)) {
                statement(depth, true);
            }
            return;
        }
        if (wd != null) {
            switch (wd) {
                case "BEGIN" -> {
                    if (isBlockBegin(i)) {
                        block(depth, sameLine);
                        return;
                    }
                }
                case "DECLARE" -> {
                    if (oracle) {
                        declareSection(depth, sameLine);
                        return;
                    }
                }
                case "IF" -> {
                    if (tsql) {
                        tsqlIf(depth, sameLine);
                    } else {
                        plIf(depth, sameLine);
                    }
                    return;
                }
                case "WHILE" -> {
                    if (tsql) {
                        emit(i, !sameLine, ind);
                        i++;
                        condition(depth, Set.of(), "WHILE");
                        tsqlBody(depth);
                    } else {
                        loopWithCondition(depth, sameLine);
                    }
                    return;
                }
                case "FOR" -> {
                    if (!tsql && hasLoopKeyword(i)) {
                        loopWithCondition(depth, sameLine);
                        return;
                    }
                }
                case "LOOP" -> {
                    if (!tsql) {
                        emit(i, !sameLine, ind);
                        i++;
                        body(depth + 1, STOP_END);
                        if (isWord(i, "END")) {
                            endLine(depth);
                        }
                        return;
                    }
                }
                case "REPEAT" -> {
                    if (!tsql && !(i + 1 < n && tok(i + 1).isSymbol("("))) {
                        repeat(depth, sameLine);
                        return;
                    }
                }
                case "CASE" -> {
                    if (caseStatement[i]) {
                        caseStatement(depth, sameLine);
                        return;
                    }
                }
                default -> {
                }
            }
        }
        sqlStatement(depth, sameLine);
    }

    private void body(int depth, Set<String> stops) {
        procDepth++;
        statementList(depth, stops);
        procDepth--;
    }

    private void block(int depth, boolean sameLine) {
        int ind = indent(depth);
        emit(i, !sameLine, ind);
        i++;
        while (i < n && (isWord(i, "TRY") || isWord(i, "CATCH") || isWord(i, "ATOMIC")
                || (isWord(i, "NOT") && isWord(i + 1, "ATOMIC")))) {
            emit(i, false, ind);
            i++;
        }
        procDepth++;
        statementList(depth + 1, tsql ? STOP_END : STOP_END_EXCEPTION);
        if (isWord(i, "EXCEPTION")) {
            emit(i, true, ind);
            i++;
            while (i < n && !isWord(i, "END")) {
                if (isWord(i, "WHEN")) {
                    emit(i, true, indent(depth + 1));
                    i++;
                    condition(depth + 1, STOP_THEN, "WHEN");
                    if (isWord(i, "THEN")) {
                        emit(i, false, ind);
                        i++;
                    }
                    statementList(depth + 2, STOP_WHEN_END);
                } else {
                    int before = i;
                    statement(depth + 2, false);
                    if (i == before) {
                        emit(i, true, indent(depth + 2));
                        i++;
                    }
                }
            }
        }
        procDepth--;
        if (isWord(i, "END")) {
            endLine(depth);
        }
    }

    private void endLine(int depth) {
        emit(i, true, indent(depth));
        i++;
        if (tsql) {
            if (isWord(i, "TRY") || isWord(i, "CATCH")) {
                emit(i, false, 0);
                i++;
            }
        } else {
            if (i < n && word(i) != null && END_FOLLOWERS.contains(word(i))) {
                emit(i, false, 0);
                i++;
            }
            int words = 0;
            while (i + words < n && words < 4 && tok(i + words).kind == Kind.WORD) {
                words++;
            }
            if (words > 0 && (i + words >= n || tok(i + words).isSymbol(";") || tok(i + words).kind == Kind.TERMINATOR)) {
                for (int j = 0; j < words; j++) {    // END name; END LOOP label; END AFTER EACH ROW;
                    emit(i, false, 0);
                    i++;
                }
            }
        }
        if (i < n && tok(i).isSymbol(";")) {
            emit(i, false, 0);
            i++;
        }
    }

    private void declareSection(int depth, boolean sameLine) {
        emit(i, !sameLine, indent(depth));
        i++;
        body(depth + 1, STOP_BEGIN);
        if (isWord(i, "BEGIN")) {
            block(depth, false);
        }
    }

    private void section(int depth) {
        body(depth + 1, STOP_BEGIN_END);
        if (isWord(i, "BEGIN")) {
            block(depth, false);
        } else if (isWord(i, "END")) {
            endLine(depth);
        }
    }

    private void plIf(int depth, boolean sameLine) {
        int ind = indent(depth);
        emit(i, !sameLine, ind);
        i++;
        condition(depth, STOP_THEN, "IF");
        if (isWord(i, "THEN")) {
            emit(i, false, ind);
            i++;
        }
        procDepth++;
        statementList(depth + 1, STOP_IF_BRANCH);
        while (isWord(i, "ELSIF") || isWord(i, "ELSEIF")) {
            emit(i, true, ind);
            i++;
            condition(depth, STOP_THEN, "IF");
            if (isWord(i, "THEN")) {
                emit(i, false, ind);
                i++;
            }
            statementList(depth + 1, STOP_IF_BRANCH);
        }
        if (isWord(i, "ELSE")) {
            emit(i, true, ind);
            i++;
            statementList(depth + 1, STOP_END);
        }
        procDepth--;
        if (isWord(i, "END")) {
            endLine(depth);
        }
    }

    private void loopWithCondition(int depth, boolean sameLine) {
        int ind = indent(depth);
        String construct = word(i);
        emit(i, !sameLine, ind);
        i++;
        procDepth++;
        condition(depth, STOP_LOOP, construct);
        if (i < n && word(i) != null && STOP_LOOP.contains(word(i))) {
            emit(i, false, ind);
            i++;
        }
        statementList(depth + 1, STOP_END);
        procDepth--;
        if (isWord(i, "END")) {
            endLine(depth);
        }
    }

    private void repeat(int depth, boolean sameLine) {
        int ind = indent(depth);
        emit(i, !sameLine, ind);
        i++;
        procDepth++;
        statementList(depth + 1, STOP_UNTIL);
        if (isWord(i, "UNTIL")) {
            emit(i, true, ind);
            i++;
            condition(depth, STOP_END, "UNTIL");
        }
        procDepth--;
        if (isWord(i, "END")) {
            endLine(depth);
        }
    }

    private void caseStatement(int depth, boolean sameLine) {
        emit(i, !sameLine, indent(depth));
        i++;
        procDepth++;
        condition(depth, STOP_WHEN_ELSE_END, "CASE");
        while (i < n) {
            if (isWord(i, "WHEN")) {
                emit(i, true, indent(depth + 1));
                i++;
                condition(depth + 1, STOP_THEN, "WHEN");
                if (isWord(i, "THEN")) {
                    emit(i, false, 0);
                    i++;
                }
                statementList(depth + 2, STOP_WHEN_ELSE_END);
            } else if (isWord(i, "ELSE")) {
                emit(i, true, indent(depth + 1));
                i++;
                statementList(depth + 2, STOP_END);
            } else {
                break;
            }
        }
        procDepth--;
        if (isWord(i, "END")) {
            endLine(depth);
        }
    }

    private void tsqlIf(int depth, boolean sameLine) {
        int ind = indent(depth);
        emit(i, !sameLine, ind);
        i++;
        condition(depth, Set.of(), "IF");
        tsqlBody(depth);
        if (isWord(i, "ELSE")) {
            emit(i, true, ind);
            i++;
            if (isWord(i, "IF")) {
                tsqlIf(depth, true);
            } else {
                tsqlBody(depth);
            }
        }
    }

    private void tsqlBody(int depth) {
        if (i >= n) {
            return;
        }
        if (isWord(i, "BEGIN") && isBlockBegin(i)) {
            block(depth, false);
        } else {
            int before = i;
            statement(depth + 1, false);
            if (i == before) {
                emit(i, true, indent(depth + 1));
                i++;
            }
        }
    }

    /** A procedural condition (IF, WHILE, WHEN, UNTIL …): AND/OR break only when it does not fit. */
    private void condition(int depth, Set<String> stops, String construct) {
        Frame f = new Frame(FrameKind.COND, indent(depth));
        f.start = i;
        f.condStops = stops;
        f.firstWord = construct;
        int end = scanEnd(f, i);
        if (end > i) {
            boolean fits = !hasForced(i, end - 1) && w.column() + 1 + flatWidth(i, end - 1) <= maxWidth;
            f.andOr = !fits;
            f.andOrIndent = w.lineIndent() + unit;
        }
        layoutSeq(f);
    }

    private void sqlStatement(int depth, boolean sameLine) {
        Frame f = new Frame(kindOf(i), indent(depth));
        f.start = i;
        f.firstWord = word(i);
        f.cteMainPending = f.kind == FrameKind.QUERY && "WITH".equals(f.firstWord);
        if (!sameLine) {
            pendingIndent = f.indent;
        }
        f.sameLine = sameLine;
        Stop stop = layoutSeq(f);
        if (stop == Stop.SECTION) {
            section(depth);
        }
    }

    private FrameKind kindOf(int k) {
        if (tok(k).isSymbol("(")) {
            return FrameKind.QUERY;
        }
        String wd = word(k);
        if (wd == null) {
            return FrameKind.GENERIC;
        }
        switch (wd) {
            case "SELECT", "WITH", "VALUES" -> {
                return FrameKind.QUERY;
            }
            case "INSERT", "UPSERT" -> {
                return FrameKind.INSERT;
            }
            case "REPLACE" -> {
                return dialect == Dialect.MYSQL || dialect == Dialect.MARIADB || dialect == Dialect.SQLITE
                        ? FrameKind.INSERT : FrameKind.GENERIC;
            }
            case "UPDATE" -> {
                return FrameKind.UPDATE;
            }
            case "DELETE" -> {
                return FrameKind.DELETE;
            }
            case "MERGE" -> {
                return FrameKind.MERGE;
            }
            case "CREATE", "ALTER" -> {
                if (oracle && isOracleHeader(k)) {
                    return FrameKind.HEADER;
                }
                if (tsql && isTsqlHeader(k)) {
                    return FrameKind.TSQL_HEADER;
                }
                for (int j = k + 1; j < Math.min(n, k + 12); j++) {
                    String x = word(j);
                    if (x == null) {
                        break;
                    }
                    switch (x) {
                        case "TABLE" -> {
                            return wd.equals("CREATE") ? FrameKind.CREATE_TABLE : FrameKind.GENERIC;
                        }
                        case "VIEW" -> {
                            return FrameKind.VIEW;
                        }
                        case "TRIGGER" -> {
                            return tsql ? FrameKind.GENERIC : FrameKind.TRIGGER;
                        }
                        case "INDEX", "SEQUENCE", "SCHEMA", "DATABASE", "USER", "ROLE", "PROCEDURE", "FUNCTION",
                             "PACKAGE", "TYPE", "SYNONYM" -> {
                            return FrameKind.GENERIC;
                        }
                        default -> {
                        }
                    }
                }
                return FrameKind.GENERIC;
            }
            default -> {
                return oracle && isOracleHeader(k) ? FrameKind.HEADER : FrameKind.GENERIC;
            }
        }
    }

    private boolean isOracleHeader(int k) {
        if (procDepth > 0 && (isWord(k, "BEFORE") || isWord(k, "AFTER") || isWord(k, "INSTEAD"))) {
            return isWord(k + 1, "STATEMENT") || isWord(k + 1, "EACH") || isWord(k + 1, "OF");   // compound trigger section
        }
        int j = k;
        if (isWord(j, "CREATE")) {
            j++;
            while (j < n && word(j) != null && CREATE_MODIFIERS.contains(word(j))) {
                j++;
            }
        } else {
            while (j < n && word(j) != null && MEMBER_MODIFIERS.contains(word(j))) {
                j++;
            }
        }
        String x = word(j);
        if (x == null) {
            return false;
        }
        return x.equals("PROCEDURE") || x.equals("FUNCTION") || x.equals("PACKAGE")
                || (x.equals("TYPE") && isWord(j + 1, "BODY"));
    }

    private boolean isTsqlHeader(int k) {
        int j = k + 1;
        if (isWord(j, "OR") && isWord(j + 1, "ALTER")) {
            j += 2;
        }
        String x = word(j);
        return x != null && (x.equals("PROC") || x.equals("PROCEDURE") || x.equals("FUNCTION") || x.equals("TRIGGER"));
    }

    private boolean hasLoopKeyword(int k) {
        for (int j = k + 1; j < n; j++) {
            FormatToken t = tok(j);
            if (t.isSymbol(";") || t.kind == Kind.TERMINATOR) {
                return false;
            }
            if (t.isSymbol("(") && match[j] > j) {
                j = match[j];
                continue;
            }
            if (isWord(j, "LOOP") || isWord(j, "DO")) {
                return true;
            }
        }
        return false;
    }

    private boolean isBlockBegin(int k) {
        if (!isWord(k, "BEGIN") || glued[k] || k + 1 >= n) {
            return false;
        }
        if (k > 0) {
            FormatToken p = tok(k - 1);
            if (p.kind == Kind.SYMBOL && !p.isSymbol(";") && !p.isSymbol(")") && !p.isSymbol(">>") && !p.isSymbol(":")) {
                return false;
            }
            String pw = word(k - 1);
            if (pw != null && Set.of("SELECT", "DISTINCT", "BY").contains(pw)) {
                return false;
            }
        }
        FormatToken nx = tok(k + 1);
        if (nx.kind == Kind.WORD) {
            return !TX_WORDS.contains(nx.upper);
        }
        return nx.isSymbol("<<") || nx.isSymbol(":");
    }

    // ----- statement body: clauses, groups, lists ------------------------------------------

    private Stop layoutSeq(Frame f) {
        while (i < n && (f.limit < 0 || i < f.limit)) {
            int k = i;
            if (f.limit < 0) {
                Stop s = boundary(f, k);
                if (s != null) {
                    return s;
                }
            }
            FormatToken t = tok(k);
            String wd = glued[k] ? null : word(k);
            if (t.isSymbol("(") && match[k] > k && (f.limit < 0 || match[k] < f.limit)) {
                paren(f, k);
                continue;
            }
            if ("CASE".equals(wd) && caseEnd[k] > k && (f.limit < 0 || caseEnd[k] < f.limit)) {
                caseExpression(k);
                continue;
            }
            if (wd != null && keyword(f, k, wd)) {
                continue;
            }
            if (t.isSymbol(",")) {
                comma(f, k);
                continue;
            }
            emit(k, false, 0);
            i = k + 1;
        }
        return Stop.END;
    }

    /** Ends the statement at {@code k}, or returns null to continue. */
    private Stop boundary(Frame f, int k) {
        FormatToken t = tok(k);
        String wd = glued[k] ? null : word(k);
        if (wd != null && f.condStops != null && f.condStops.contains(wd)) {
            return Stop.COND_END;
        }
        if (k == f.start) {
            return null;
        }
        if (t.isSymbol(";")) {
            emit(k, false, 0);
            i = k + 1;
            return Stop.SEMI;
        }
        if (t.kind == Kind.TERMINATOR) {
            return Stop.BOUNDARY;
        }
        if (wd == null) {
            return null;
        }
        if (f.kind == FrameKind.HEADER && !f.sectionSeen && (wd.equals("IS") || wd.equals("AS"))
                && !isWord(k + 1, "LANGUAGE") && !isWord(k + 1, "EXTERNAL")) {
            f.sectionSeen = true;
            emit(k, false, 0);
            i = k + 1;
            return Stop.SECTION;
        }
        if (f.kind == FrameKind.TRIGGER && oracle && wd.equals("COMPOUND") && isWord(k + 1, "TRIGGER")) {
            emit(k, false, 0);
            emit(k + 1, false, 0);
            i = k + 2;
            return Stop.SECTION;
        }
        if (f.kind == FrameKind.TSQL_HEADER && wd.equals("AS") && tsqlBodyFollows(k)) {
            emit(k, true, f.baseIndent);
            i = k + 1;
            return Stop.BOUNDARY;
        }
        return endsStatement(f, k) ? Stop.BOUNDARY : null;
    }

    /** True when the word at {@code k} starts a new statement or block (no side effects). */
    private boolean endsStatement(Frame f, int k) {
        String wd = glued[k] ? null : word(k);
        if (wd == null) {
            return false;
        }
        if (tsql) {
            return tsqlStarts(f, k);
        }
        if (wd.equals("BEGIN") && isBlockBegin(k)) {
            return true;
        }
        if (oracle && wd.equals("DECLARE")) {
            return true;
        }
        if (k + 2 < n && tok(k + 1).isSymbol(":") && word(k + 2) != null
                && Set.of("BEGIN", "LOOP", "WHILE", "REPEAT", "FOR").contains(word(k + 2)) && !Keywords.isCaseable(wd)) {
            return true;                              // label: BEGIN
        }
        return wd.equals("END") && procDepth > 0 && !caseEndToken[k];
    }

    private boolean tsqlBodyFollows(int k) {
        if (k + 1 >= n || tok(k + 1).isSymbol(";")) {
            return true;
        }
        String x = word(k + 1);
        return x != null && (TSQL_STARTERS.contains(x) || x.equals("WITH"));
    }

    private boolean tsqlStarts(Frame f, int k) {
        String wd = word(k);
        if (wd == null || glued[k] || !TSQL_STARTERS.contains(wd)) {
            return false;
        }
        if (k == 0) {
            return true;
        }
        FormatToken p = tok(k - 1);
        if (p.isSymbol(";")) {
            return true;
        }
        if (p.isSymbol(",") || p.isSymbol("(") || p.isSymbol(".")) {
            return false;
        }
        String pw = word(k - 1);
        String first = f.firstWord == null ? "" : f.firstWord;
        if (pw != null && Set.of("OR", "AND", "NOT", "IS", "IN", "LIKE", "BY").contains(pw) && !glued[k - 1]) {
            return false;                             // CREATE OR ALTER, expressions
        }
        if (TSQL_DML.contains(wd)) {
            if (pw != null && TSQL_DML_CONTINUATION.contains(pw)) {
                return false;
            }
            if (Set.of("GRANT", "REVOKE", "DENY").contains(first)) {
                return false;
            }
        }
        switch (wd) {
            case "SELECT" -> {
                return !(f.kind == FrameKind.INSERT || f.kind == FrameKind.VIEW || f.kind == FrameKind.CREATE_TABLE
                        || f.cteMainPending);
            }
            case "INSERT", "UPDATE", "DELETE", "MERGE" -> {
                if ("ON".equals(pw) && !wd.equals("INSERT")) {
                    return false;                     // ON DELETE CASCADE
                }
                return !f.cteMainPending;
            }
            case "SET" -> {
                if (f.kind == FrameKind.UPDATE || f.kind == FrameKind.MERGE || first.equals("ALTER")) {
                    return false;
                }
                return !("DELETE".equals(pw) || "UPDATE".equals(pw));
            }
            case "EXEC", "EXECUTE" -> {
                return f.kind != FrameKind.INSERT;
            }
            case "IF" -> {
                return pw == null || !OBJECT_WORDS.contains(pw);
            }
            case "FETCH" -> {
                return pw == null || !(pw.equals("ROWS") || pw.equals("ROW") || pw.equals("ONLY"));
            }
            case "ALTER", "DROP", "ENABLE", "DISABLE" -> {
                return !first.equals("ALTER") && !Set.of("GRANT", "REVOKE", "DENY").contains(first);
            }
            case "CREATE" -> {
                return !Set.of("GRANT", "REVOKE", "DENY").contains(first);
            }
            case "GRANT", "REVOKE", "DENY" -> {
                return !"WITH".equals(pw);
            }
            default -> {
                return true;
            }
        }
    }

    private boolean keyword(Frame f, int k, String wd) {
        if (wd.equals("BETWEEN")) {
            f.between++;
            return false;
        }
        if (f.kind == FrameKind.CASE) {
            if (wd.equals("WHEN") || wd.equals("ELSE")) {
                emit(k, true, f.indent);
                i = k + 1;
                return true;
            }
            return false;
        }
        if (f.kind == FrameKind.LIST) {
            return andOr(f, k, wd);
        }
        if (k != f.start) {
            int sw = switchKind(f, k, wd);
            if (sw == 2) {
                return true;
            }
        }
        Clause c = clauseAt(f, k);
        if (c != null) {
            clause(f, k, c);
            return true;
        }
        if (f.joinOn && wd.equals("ON")) {
            emit(k, false, 0);
            i = k + 1;
            f.joinOn = false;
            f.andOr = true;
            f.andOrIndent = w.lineIndent() + unit;
            return true;
        }
        if (f.kind == FrameKind.MERGE && wd.equals("THEN")) {
            emit(k, false, 0);
            i = k + 1;
            f.inAction = true;
            f.andOr = false;
            pendingIndent = f.indent + unit;
            return true;
        }
        if (andOr(f, k, wd)) {
            return true;
        }
        if (f.kind == FrameKind.TSQL_HEADER && !f.paramsDone && wd.startsWith("@")) {
            f.paramsDone = true;
            int end = k;
            while (end < n && !(isWord(end, "AS") && tsqlBodyFollows(end)) && !tok(end).isSymbol(";")) {
                end = tok(end).isSymbol("(") && match[end] > end ? match[end] + 1 : end + 1;
            }
            if (end > k && (countCommas(k, end) >= 3 || hasForced(k, end - 1)
                    || w.column() + 1 + flatWidth(k, end - 1) > maxWidth)) {
                f.listBroken = true;
                f.itemIndent = f.baseIndent + unit;
                pendingIndent = f.itemIndent;
            }
        }
        return false;
    }

    /** 0: nothing, 1: frame kind changed, 2: token consumed. */
    private int switchKind(Frame f, int k, String wd) {
        if (wd.equals("SELECT") || (wd.equals("WITH") && isCte(k))) {
            switch (f.kind) {
                case INSERT, VIEW, CREATE_TABLE -> {
                    f.fromInsert = f.kind == FrameKind.INSERT;
                    f.kind = FrameKind.QUERY;
                    resetClause(f);
                    return 1;
                }
                case GENERIC, COND, TRIGGER -> {
                    if (!NO_QUERY_SWITCH.contains(f.firstWord == null ? "" : f.firstWord)) {
                        f.kind = FrameKind.QUERY;
                        f.indent += unit;
                        resetClause(f);
                        return 1;
                    }
                    return 0;
                }
                case QUERY -> {
                    if (wd.equals("SELECT")) {
                        f.cteMainPending = false;
                    }
                    return 0;
                }
                default -> {
                    return 0;
                }
            }
        }
        boolean dml = wd.equals("INSERT") || wd.equals("UPDATE") || wd.equals("DELETE") || wd.equals("MERGE");
        if (dml && f.kind == FrameKind.QUERY && f.cteMainPending) {
            f.cteMainPending = false;
            f.kind = kindOf(k);
            resetClause(f);
            emit(k, true, f.indent);
            i = k + 1;
            return 2;
        }
        if (dml && f.kind == FrameKind.GENERIC && "FORALL".equals(f.firstWord)) {
            f.kind = kindOf(k);
            f.indent += unit;
            resetClause(f);
            emit(k, true, f.indent);
            i = k + 1;
            return 2;
        }
        return 0;
    }

    private boolean isCte(int k) {
        int j = k + 1;
        if (isWord(j, "RECURSIVE")) {
            j++;
        }
        if (j >= n || (tok(j).kind != Kind.WORD && tok(j).kind != Kind.QUOTED)) {
            return false;
        }
        j++;
        if (j < n && tok(j).isSymbol("(") && match[j] > j) {
            j = match[j] + 1;
        }
        return isWord(j, "AS") && ((j + 1 < n && tok(j + 1).isSymbol("(")) || isWord(j + 1, "MATERIALIZED")
                || isWord(j + 1, "NOT"));
    }

    private static void resetClause(Frame f) {
        f.clause = null;
        f.listBroken = false;
        f.andOr = false;
        f.between = 0;
        f.joinOn = false;
        f.cteList = false;
        f.fill = false;
    }

    private Clause clauseAt(Frame f, int k) {
        String wd = word(k);
        if (wd == null || glued[k]) {
            return null;
        }
        return switch (f.kind) {
            case QUERY -> queryClause(f, k, wd);
            case INSERT -> insertClause(f, k, wd);
            case UPDATE -> dmlClause(f, k, wd, true);
            case DELETE -> dmlClause(f, k, wd, false);
            case MERGE -> mergeClause(f, k, wd);
            case TRIGGER -> triggerClause(k, wd);
            default -> null;
        };
    }

    private Clause queryClause(Frame f, int k, String wd) {
        switch (wd) {
            case "SELECT" -> {
                return new Clause("SELECT", 1, Body.LIST, 3, false);
            }
            case "WITH" -> {
                if (k == f.start || isCte(k)) {
                    return new Clause("WITH", isWord(k + 1, "RECURSIVE") ? 2 : 1, Body.CTE, MANY, false);
                }
                return null;
            }
            case "VALUES" -> {
                return k == f.start ? new Clause("VALUES", 1, Body.LIST, 1, false) : null;
            }
            case "FROM" -> {
                return isWord(k - 1, "DISTINCT") ? null : new Clause("FROM", 1, Body.LIST, MANY, false);
            }
            case "WHERE", "HAVING", "QUALIFY" -> {
                return new Clause(wd, 1, Body.COND, MANY, false);
            }
            case "GROUP" -> {
                return isWord(k + 1, "BY") ? new Clause("GROUP BY", 2, Body.LIST, MANY, false) : null;
            }
            case "ORDER" -> {
                if (isWord(k + 1, "BY")) {
                    return new Clause("ORDER BY", 2, Body.LIST, MANY, false);
                }
                return isWord(k + 1, "SIBLINGS") && isWord(k + 2, "BY")
                        ? new Clause("ORDER BY", 3, Body.LIST, MANY, false) : null;
            }
            case "CONNECT" -> {
                if (isWord(k + 1, "BY")) {
                    return new Clause("CONNECT BY", isWord(k + 2, "NOCYCLE") ? 3 : 2, Body.COND, MANY, false);
                }
                return null;
            }
            case "START" -> {
                return isWord(k + 1, "WITH") ? new Clause("START WITH", 2, Body.COND, MANY, false) : null;
            }
            case "UNION", "INTERSECT", "EXCEPT", "MINUS" -> {
                int len = isWord(k + 1, "ALL") || isWord(k + 1, "DISTINCT") ? 2 : 1;
                return new Clause(wd, len, Body.SETOP, MANY, false);
            }
            case "INTO" -> {
                return "SELECT".equals(f.clause) ? new Clause("INTO", 1, Body.LIST, MANY, false) : null;
            }
            case "BULK" -> {
                return isWord(k + 1, "COLLECT") && isWord(k + 2, "INTO")
                        ? new Clause("INTO", 3, Body.LIST, MANY, false) : null;
            }
            case "LIMIT", "OFFSET" -> {
                return f.clause != null && !"SELECT".equals(f.clause) && k + 1 < n
                        && !tok(k + 1).isSymbol(",") && !tok(k + 1).isSymbol(")") && !tok(k + 1).isSymbol("=")
                        ? new Clause(wd, 1, Body.PLAIN, MANY, false) : null;
            }
            case "FETCH" -> {
                return isWord(k + 1, "FIRST") || isWord(k + 1, "NEXT") ? new Clause(wd, 1, Body.PLAIN, MANY, false) : null;
            }
            case "FOR" -> {
                String x = word(k + 1);
                return x != null && Set.of("UPDATE", "XML", "JSON", "READ", "SHARE", "NO", "BROWSE", "KEY").contains(x)
                        ? new Clause("FOR", 1, Body.PLAIN, MANY, false) : null;
            }
            case "OPTION" -> {
                return tsql && k + 1 < n && tok(k + 1).isSymbol("(") ? new Clause(wd, 1, Body.PLAIN, MANY, false) : null;
            }
            case "RETURNING" -> {
                return new Clause(wd, 1, Body.LIST, MANY, false);
            }
            case "ON" -> {
                return f.fromInsert ? insertClause(f, k, wd) : null;
            }
            case "SET" -> {
                return f.fromInsert && "ON CONFLICT".equals(f.clause) ? new Clause("SET", 1, Body.LIST, 1, false) : null;
            }
            default -> {
                return joinClause(k, wd);
            }
        }
    }

    private Clause joinClause(int k, String wd) {
        int j = k;
        if (wd.equals("STRAIGHT_JOIN")) {
            return new Clause("JOIN", 1, Body.JOIN, MANY, false);
        }
        if ((wd.equals("CROSS") || wd.equals("OUTER")) && isWord(k + 1, "APPLY")) {
            return new Clause("JOIN", 2, Body.JOIN, MANY, false);
        }
        if (isWord(j, "NATURAL")) {
            j++;
        }
        if (isWord(j, "LEFT") || isWord(j, "RIGHT") || isWord(j, "FULL")) {
            j++;
            if (isWord(j, "OUTER")) {
                j++;
            }
        } else if (isWord(j, "INNER") || isWord(j, "CROSS")) {
            j++;
        }
        return isWord(j, "JOIN") && !glued[j] ? new Clause("JOIN", j - k + 1, Body.JOIN, MANY, false) : null;
    }

    private Clause insertClause(Frame f, int k, String wd) {
        if (isWord(f.start + 1, "ALL") || isWord(f.start + 1, "FIRST")) {
            return switch (wd) {                      // Oracle multi-table insert
                case "INTO", "WHEN", "ELSE" -> k == f.start + 2 ? null : new Clause(wd, 1, Body.PLAIN, MANY, false, 1);
                default -> null;
            };
        }
        switch (wd) {
            case "VALUES", "VALUE" -> {
                return f.clause == null || f.clause.equals("OUTPUT") ? new Clause("VALUES", 1, Body.LIST, 1, false) : null;
            }
            case "OUTPUT", "RETURNING" -> {
                return new Clause(wd, 1, Body.LIST, MANY, false);
            }
            case "ON" -> {
                if (isWord(k + 1, "CONFLICT")) {
                    return new Clause("ON CONFLICT", 2, Body.PLAIN, MANY, false);
                }
                if (isWord(k + 1, "DUPLICATE") && isWord(k + 2, "KEY") && isWord(k + 3, "UPDATE")) {
                    return new Clause("ON DUPLICATE", 4, Body.LIST, 1, false);
                }
                return null;
            }
            case "DEFAULT" -> {
                return isWord(k + 1, "VALUES") ? new Clause("DEFAULT", 2, Body.PLAIN, MANY, false) : null;
            }
            case "EXEC", "EXECUTE" -> {
                return tsql ? new Clause(wd, 1, Body.PLAIN, MANY, false) : null;
            }
            case "SET" -> {
                return new Clause("SET", 1, Body.LIST, 1, false);
            }
            case "WHERE" -> {
                return new Clause("WHERE", 1, Body.COND, MANY, false);
            }
            default -> {
                return null;
            }
        }
    }

    private Clause dmlClause(Frame f, int k, String wd, boolean update) {
        switch (wd) {
            case "SET" -> {
                return update ? new Clause("SET", 1, Body.LIST, 1, false) : null;
            }
            case "FROM" -> {
                return isWord(k - 1, "DELETE") || isWord(k - 1, "DISTINCT") ? null
                        : new Clause("FROM", 1, Body.LIST, MANY, false);
            }
            case "USING" -> {
                return update ? null : new Clause("USING", 1, Body.LIST, MANY, false);
            }
            case "WHERE" -> {
                return new Clause("WHERE", 1, Body.COND, MANY, false);
            }
            case "RETURNING", "OUTPUT" -> {
                return new Clause(wd, 1, Body.LIST, MANY, false);
            }
            case "ORDER" -> {
                return isWord(k + 1, "BY") ? new Clause("ORDER BY", 2, Body.LIST, MANY, false) : null;
            }
            case "LIMIT" -> {
                return f.clause != null ? new Clause("LIMIT", 1, Body.PLAIN, MANY, false) : null;
            }
            default -> {
                return joinClause(k, wd);
            }
        }
    }

    private Clause mergeClause(Frame f, int k, String wd) {
        switch (wd) {
            case "USING" -> {
                return new Clause("USING", 1, Body.PLAIN, MANY, false);
            }
            case "ON" -> {
                return f.clause != null && f.clause.equals("USING") ? new Clause("ON", 1, Body.COND, MANY, false) : null;
            }
            case "WHEN" -> {
                return new Clause("WHEN", 1, Body.PLAIN, MANY, false);
            }
            case "SET" -> {
                return new Clause("SET", 1, Body.LIST, 1, isWord(k - 1, "UPDATE"));
            }
            case "VALUES" -> {
                return new Clause("VALUES", 1, Body.LIST, MANY, false);
            }
            case "WHERE" -> {
                return f.inAction ? new Clause("WHERE", 1, Body.COND, MANY, false) : null;
            }
            case "DELETE" -> {
                return f.inAction && !isWord(k - 1, "THEN") ? new Clause("DELETE", 1, Body.PLAIN, MANY, false) : null;
            }
            case "OUTPUT", "RETURNING" -> {
                return new Clause(wd, 1, Body.LIST, MANY, false);
            }
            default -> {
                return null;
            }
        }
    }

    private Clause triggerClause(int k, String wd) {
        switch (wd) {
            case "BEFORE", "AFTER", "REFERENCING", "WHEN", "FOLLOWS", "PRECEDES", "EXECUTE" -> {
                return new Clause(wd, 1, Body.PLAIN, MANY, false);
            }
            case "INSTEAD" -> {
                return isWord(k + 1, "OF") ? new Clause(wd, 2, Body.PLAIN, MANY, false) : null;
            }
            case "FOR" -> {
                return isWord(k + 1, "EACH") ? new Clause(wd, 1, Body.PLAIN, MANY, false) : null;
            }
            default -> {
                return null;
            }
        }
    }

    private void clause(Frame f, int k, Clause c) {
        resetClause(f);
        f.clause = c.key();
        if (f.kind == FrameKind.MERGE && Set.of("WHEN", "USING", "ON", "OUTPUT", "RETURNING").contains(c.key())) {
            f.inAction = false;
        }
        boolean actionClause = f.kind == FrameKind.MERGE && f.inAction
                && Set.of("SET", "VALUES", "WHERE", "DELETE").contains(c.key());
        int ind = f.indent + (actionClause ? unit : 0) + c.extra() * unit;
        int last = k + c.length() - 1;
        emit(k, !c.inline() && !(k == f.start && f.sameLine), ind);
        for (int j = k + 1; j <= last; j++) {
            emit(j, false, 0);
        }
        i = last + 1;
        switch (c.body()) {
            case LIST -> {
                if (c.key().equals("SELECT")) {
                    selectModifiers(f);
                }
                int end = scanEnd(f, i);
                if (end > i) {
                    int commas = countCommas(i, end);
                    boolean broken = commas >= c.threshold() || (commas > 0 && (hasForced(i, end - 1)
                            || w.column() + 1 + flatWidth(i, end - 1) > maxWidth));
                    if (broken) {
                        f.listBroken = true;
                        f.itemIndent = w.lineIndent() + unit;
                        pendingIndent = f.itemIndent;
                    }
                }
            }
            case COND -> {
                f.andOr = true;
                f.andOrIndent = w.lineIndent() + unit;
            }
            case JOIN -> f.joinOn = true;
            case SETOP -> pendingIndent = f.indent;
            case CTE -> f.cteList = true;
            case PLAIN -> {
            }
        }
    }

    private void selectModifiers(Frame f) {
        while (i < n && (f.limit < 0 || i < f.limit)) {
            String wd = glued[i] ? null : word(i);
            if (wd == null) {
                return;
            }
            if (SELECT_MODIFIERS.contains(wd)) {
                emit(i, false, 0);
                i++;
                if (wd.equals("DISTINCT") && isWord(i, "ON") && i + 1 < n && tok(i + 1).isSymbol("(") && match[i + 1] > i) {
                    emit(i, false, 0);
                    i++;
                    paren(f, i);
                }
            } else if (wd.equals("TOP")) {
                emit(i, false, 0);
                i++;
                if (i < n && tok(i).isSymbol("(") && match[i] > i) {
                    paren(f, i);
                } else if (i < n && tok(i).kind != Kind.SYMBOL) {
                    emit(i, false, 0);
                    i++;
                }
                if (isWord(i, "PERCENT")) {
                    emit(i, false, 0);
                    i++;
                }
                if (isWord(i, "WITH") && isWord(i + 1, "TIES")) {
                    emit(i, false, 0);
                    emit(i + 1, false, 0);
                    i += 2;
                }
            } else {
                return;
            }
        }
    }

    /** End (exclusive) of the current clause or condition starting at {@code from}. */
    private int scanEnd(Frame f, int from) {
        int lim = f.limit < 0 ? n : f.limit;
        int j = from;
        while (j < lim) {
            FormatToken t = tok(j);
            String wd = glued[j] ? null : word(j);
            if (f.limit < 0) {
                if (t.isSymbol(";") || t.kind == Kind.TERMINATOR) {
                    return j;
                }
                if (wd != null) {
                    if (f.condStops != null && f.condStops.contains(wd)) {
                        return j;
                    }
                    if (j != f.start && endsStatement(f, j)) {
                        return j;
                    }
                    if (f.kind == FrameKind.HEADER && (wd.equals("IS") || wd.equals("AS"))) {
                        return j;
                    }
                    if (f.kind == FrameKind.TSQL_HEADER && wd.equals("AS") && tsqlBodyFollows(j)) {
                        return j;
                    }
                }
            }
            if (t.isSymbol("(") && match[j] > j) {
                j = match[j] + 1;
                continue;
            }
            if ("CASE".equals(wd) && caseEnd[j] > j) {
                j = caseEnd[j] + 1;
                continue;
            }
            if (j > from && wd != null && f.kind != FrameKind.COND && clauseAt(f, j) != null) {
                return j;
            }
            if (f.kind == FrameKind.MERGE && "THEN".equals(wd)) {
                return j;
            }
            j++;
        }
        return lim;
    }

    private int countCommas(int from, int to) {
        int commas = 0;
        for (int j = from; j < to; j++) {
            FormatToken t = tok(j);
            if (t.isSymbol("(") && match[j] > j) {
                j = match[j];
            } else if (isWord(j, "CASE") && caseEnd[j] > j) {
                j = caseEnd[j];
            } else if (t.isSymbol(",")) {
                commas++;
            }
        }
        return commas;
    }

    private boolean andOr(Frame f, int k, String wd) {
        if (!wd.equals("AND") && !wd.equals("OR")) {
            return false;
        }
        if (wd.equals("AND") && f.between > 0) {
            f.between--;
            return false;
        }
        if (!f.andOr) {
            return false;
        }
        emit(k, true, f.andOrIndent);
        i = k + 1;
        return true;
    }

    private void comma(Frame f, int k) {
        if (f.listBroken) {
            if (options.commaStyle() == FormatOptions.CommaStyle.LEADING || forced[sig[k]]) {
                emit(k, true, f.itemIndent);
            } else {
                emit(k, false, 0);
                pendingIndent = f.itemIndent;
            }
        } else if (f.fill) {
            emit(k, false, 0);
            int next = k + 1;
            int lim = f.limit < 0 ? n : f.limit;
            if (next < lim) {
                int end = next;
                while (end < lim && !tok(end).isSymbol(",")) {
                    end++;
                }
                if (w.column() + 1 + flatWidth(next, end - 1) > maxWidth) {
                    pendingIndent = f.itemIndent;
                }
            }
        } else if (f.cteList) {
            emit(k, false, 0);
            pendingIndent = f.indent;
        } else {
            emit(k, false, 0);
        }
        i = k + 1;
    }

    private void paren(Frame f, int k) {
        int close = match[k];
        boolean subquery = isSubquery(k);
        boolean force = false;
        int commas = countCommas(k + 1, close);
        if (f.kind == FrameKind.CREATE_TABLE && !f.tableParenDone) {
            f.tableParenDone = true;
            force = !subquery && close > k + 1;
        } else if ((f.kind == FrameKind.HEADER || f.kind == FrameKind.TSQL_HEADER) && !f.paramsDone) {
            f.paramsDone = true;
            force = commas >= 3;
        } else if (f.cteList && subquery) {
            force = true;
        }
        int width = flatWidth(k, close);
        boolean fits = !hasForced(k, close) && projectedColumn(k) + width <= maxWidth;
        if (close == k + 1 || (!force && fits && (!subquery || (width <= 60 && !hasAndOr(k + 1, close))))) {
            writeFlat(k, close);
            return;
        }
        emit(k, false, 0);
        i = k + 1;
        int outer = w.lineIndent();
        int inner = outer + unit;
        Frame g;
        if (subquery) {
            g = new Frame(FrameKind.QUERY, inner);
            g.firstWord = word(k + 1);
            g.cteMainPending = "WITH".equals(g.firstWord);
        } else {
            g = new Frame(FrameKind.LIST, inner);
            if (commas > 0) {
                g.itemIndent = inner;
                g.fill = !force && allAtoms(k + 1, close);
                g.listBroken = !g.fill;
            } else {
                g.andOr = true;
                g.andOrIndent = inner;
            }
        }
        g.start = k + 1;
        g.limit = close;
        pendingIndent = inner;
        layoutSeq(g);
        emit(close, true, outer);
        i = close + 1;
    }

    private boolean isSubquery(int open) {
        int first = open + 1;
        if (first >= n || first >= match[open]) {
            return false;
        }
        if (isWord(first, "SELECT") || isWord(first, "WITH")) {
            return true;
        }
        if (tok(first).isSymbol("(") && match[first] > first && isSubquery(first)) {
            int after = match[first] + 1;
            return after < match[open] && word(after) != null && SET_OPERATORS.contains(word(after));
        }
        return false;
    }

    private boolean hasAndOr(int from, int to) {
        for (int j = from; j < to; j++) {
            if (tok(j).isSymbol("(") && match[j] > j) {
                j = match[j];
            } else if (isWord(j, "AND") || isWord(j, "OR")) {
                return true;
            }
        }
        return false;
    }

    /** True for a list of plain literals ({@code IN (1, 2, 3)}), which is packed rather than one per line. */
    private boolean allAtoms(int from, int to) {
        int itemTokens = 0;
        for (int j = from; j < to; j++) {
            FormatToken t = tok(j);
            if (t.isSymbol(",")) {
                itemTokens = 0;
                continue;
            }
            boolean literal = t.kind == Kind.NUMBER || t.kind == Kind.STRING || t.isSymbol("-") || t.isSymbol("+");
            if (!literal || ++itemTokens > 2) {
                return false;
            }
        }
        return true;
    }

    private void caseExpression(int k) {
        int end = caseEnd[k];
        if (!hasForced(k, end) && countWhens(k, end) <= 2
                && projectedColumn(k) + flatWidth(k, end) + trailingWidth(end) <= maxWidth) {
            writeFlat(k, end);
            return;
        }
        emit(k, false, 0);
        i = k + 1;
        int base = w.lineIndent();
        Frame cf = new Frame(FrameKind.CASE, base + unit);
        cf.start = i;
        cf.limit = end;
        layoutSeq(cf);
        emit(end, true, base);
        i = end + 1;
    }

    private int countWhens(int caseStart, int end) {
        int whens = 0;
        for (int j = caseStart + 1; j < end; j++) {
            if (tok(j).isSymbol("(") && match[j] > j) {
                j = match[j];
            } else if (isWord(j, "CASE") && caseEnd[j] > j) {
                j = caseEnd[j];
            } else if (isWord(j, "WHEN")) {
                whens++;
            }
        }
        return whens;
    }

    /** Width of what follows a group on its line (an alias, a comparison), up to a list or clause boundary. */
    private int trailingWidth(int end) {
        int j = end + 1;
        int last = end;
        while (j < n && j <= end + 3) {
            FormatToken t = tok(j);
            if (t.isSymbol(",") || t.isSymbol(")") || t.isSymbol(";") || t.isSymbol("(") || t.kind == Kind.TERMINATOR
                    || (t.kind == Kind.WORD && Keywords.isCaseable(t.upper) && !t.upper.equals("AS"))) {
                break;
            }
            last = j++;
        }
        return last == end ? 0 : flatWidth(end + 1, last) + space[sig[end + 1]];
    }

    private void writeFlat(int from, int to) {
        for (int j = from; j <= to; j++) {
            emit(j, false, 0);
        }
        i = to + 1;
    }

    // ----- emission ------------------------------------------------------------------------

    private void emit(int k, boolean newline, int indent) {
        FormatToken t = tok(k);
        int a = sig[k];
        boolean wantNl = newline;
        int ind = indent;
        boolean layoutNl = newline;
        if (!wantNl && pendingIndent >= 0) {
            wantNl = true;
            ind = pendingIndent;
            layoutNl = true;
        }
        pendingIndent = -1;
        int from = k == 0 ? 0 : sig[k - 1] + 1;
        boolean afterOwnBlock = false;
        for (int c = from; c < a; c++) {
            afterOwnBlock = emitComment(c, wantNl ? ind : contIndent());
        }
        boolean nl = wantNl;
        if (forced[a]) {
            if (!nl) {
                ind = contIndent();
            }
            nl = true;
        } else if (nl && afterOwnBlock && t.newlinesBefore == 0) {
            nl = false;
        } else if (nl && from == a && k > 0 && !t.spaceBefore && !canSeparate(k - 1, k)) {
            nl = false;
        }
        String text = Keywords.apply(t, a > 0 ? all.get(a - 1) : null, a + 1 < all.size() ? all.get(a + 1) : null,
                options.keywordCase());
        if (nl) {
            w.newline(ind, t.newlinesBefore >= 2);
            if (layoutNl) {
                anchor = ind;
            }
            w.write(text, false);
        } else {
            w.write(text, space[a] == 1);
        }
    }

    /** Emits a comment; returns true when it was a block comment placed on its own line. */
    private boolean emitComment(int c, int ownIndent) {
        FormatToken ct = all.get(c);
        boolean own = ct.newlinesBefore > 0 || forced[c];
        if (own) {
            w.newline(ownIndent, ct.newlinesBefore >= 2);
            w.write(ct.text, false);
            return ct.kind == Kind.BLOCK_COMMENT;
        }
        w.write(ct.text, space[c] == 1);
        return false;
    }

    private void emitTerminator(int k) {
        int a = sig[k];
        int from = k == 0 ? 0 : sig[k - 1] + 1;
        for (int c = from; c < a; c++) {
            emitComment(c, 0);
        }
        FormatToken t = tok(k);
        if (t.newlinesBefore > 0 || forced[a]) {
            w.newline(0, false);
            w.write(t.text, false);
        } else {
            w.write(t.text, t.spaceBefore);
        }
        pendingIndent = -1;
    }

    private int contIndent() {
        return anchor + unit;
    }

    private int projectedColumn(int k) {
        if (pendingIndent >= 0) {
            return pendingIndent;
        }
        if (!w.hasLineContent()) {
            return w.column();
        }
        return w.column() + space[sig[k]];
    }

    /** May a line break go between touching tokens {@code kp} and {@code k}? */
    private boolean canSeparate(int kp, int k) {
        FormatToken a = tok(kp);
        FormatToken b = tok(k);
        if (a.isSymbol(",") || a.isSymbol(";") || a.isSymbol("(") || a.isSymbol(")")) {
            return true;
        }
        if (b.isSymbol("(") || b.isSymbol(")") || b.isSymbol(",") || b.isSymbol(";")) {
            return true;
        }
        if ((a.kind == Kind.STRING || a.kind == Kind.QUOTED || a.kind == Kind.NUMBER) && b.kind == Kind.WORD) {
            return true;
        }
        if (a.kind == Kind.SYMBOL && BIN_OPS.contains(a.text) && b.kind != Kind.SYMBOL) {
            return true;
        }
        return b.kind == Kind.SYMBOL && BIN_OPS.contains(b.text) && a.kind != Kind.SYMBOL;
    }

    /** Space between adjacent tokens when they are printed on one line. */
    private static boolean wantSpace(FormatToken a, FormatToken b) {
        boolean orig = b.spaceBefore;
        if (a.isComment() || b.isComment() || b.kind == Kind.TERMINATOR) {
            return orig;
        }
        if (b.isSymbol(",") || b.isSymbol(";") || b.isSymbol(")") || a.isSymbol("(")) {
            return false;
        }
        if (a.isSymbol(",")) {
            return true;
        }
        if (b.isSymbol("(")) {
            return orig || (a.kind == Kind.WORD && KW_SPACE_PAREN.contains(a.upper));
        }
        if (a.isSymbol(".") || b.isSymbol(".")) {
            return orig;
        }
        if (b.kind == Kind.SYMBOL && BIN_OPS.contains(b.text) && isOperand(a, true)) {
            return true;
        }
        if (a.kind == Kind.SYMBOL && BIN_OPS.contains(a.text) && isOperand(b, false)) {
            return true;
        }
        return orig;
    }

    /**
     * True for a token that may get a space between itself and a touching comparison operator. A word
     * must end (left side) or start (right side) with a plain name character, so that a lexing
     * difference at an unusual character can never split a real operator.
     */
    private static boolean isOperand(FormatToken t, boolean left) {
        if (t.kind == Kind.WORD) {
            char c = left ? t.text.charAt(t.text.length() - 1) : t.text.charAt(0);
            return Character.isLetterOrDigit(c) || c == '_' || (!left && (c == '@' || c == '$' || c == '&'));
        }
        return t.kind == Kind.QUOTED || t.kind == Kind.STRING || t.kind == Kind.NUMBER || t.isSymbol(")");
    }

    // ----- token helpers -------------------------------------------------------------------

    private FormatToken tok(int k) {
        return all.get(sig[k]);
    }

    private String word(int k) {
        if (k < 0 || k >= n) {
            return null;
        }
        return tok(k).upper;
    }

    private boolean isWord(int k, String upper) {
        return k >= 0 && k < n && upper.equals(tok(k).upper) && !glued[k];
    }

    private int indent(int depth) {
        return depth * unit;
    }

    private long rawWidth(int fromSig, int toSig) {
        int a = sig[fromSig];
        int b = sig[toSig];
        return widthPrefix[b + 1] - widthPrefix[a] - space[a];
    }

    private int flatWidth(int fromSig, int toSig) {
        if (toSig < fromSig) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE / 4, rawWidth(fromSig, toSig));
    }

    private boolean hasForced(int fromSig, int toSig) {
        if (toSig < fromSig) {
            return false;
        }
        int a = sig[fromSig];
        int b = sig[toSig];
        return forcedPrefix[b + 1] - forcedPrefix[a + 1] > 0 || multiPrefix[b + 1] - multiPrefix[a] > 0;
    }
}
