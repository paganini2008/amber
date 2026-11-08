package com.github.amber.format;

import com.github.amber.core.Dialect;
import com.github.amber.core.source.SourceDecoder;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ScriptParse;
import com.github.amber.dialect.split.StatementUnit;
import com.github.amber.dialect.split.UnitKind;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round trip over the whole public corpus ({@code src/test/resources/corpus}, all dialects):
 * for every file, formatting must not throw, must keep every unit's tokens and comments, must keep the
 * parse status and unit count of the script, and must be idempotent (with the default options and with
 * an alternative set: indent 2, lower case, 60 columns, leading commas). Statements the safety check kept
 * as written are counted and their reasons printed.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class FormatCorpusTest {

    private static final Path CORPUS = Path.of("src/test/resources/corpus");
    private static final long PARSE_TIMEOUT_SECONDS = 30;
    private static final Map<Dialect, String> REPORT = new ConcurrentSkipListMap<>();
    private static final FormatOptions ALTERNATIVE = new FormatOptions(2, FormatOptions.KeywordCase.LOWER, 60,
            FormatOptions.CommaStyle.LEADING);

    @TestFactory
    Stream<DynamicTest> corpus() {
        return Stream.of(Dialect.values())
                .filter(d -> Files.isDirectory(CORPUS.resolve(d.id())))
                .map(d -> DynamicTest.dynamicTest(d.displayName(), () -> run(d)));
    }

    private void run(Dialect dialect) throws Exception {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(CORPUS.resolve(dialect.id()))) {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".sql"))
                    .sorted()
                    .toList();
        }
        DialectFrontend frontend = DialectFrontends.get(dialect);
        int units = 0;
        int changedFiles = 0;
        int skippedUnits = 0;
        int parseCompared = 0;
        long formatNanos = 0;
        long lines = 0;
        Map<String, Integer> reasons = new TreeMap<>();
        List<String> failures = new ArrayList<>();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            for (Path file : files) {
                String name = CORPUS.relativize(file).toString();
                String text = SourceDecoder.decode(Files.readAllBytes(file)).text();
                lines += text.lines().count();
                FormatResult result;
                long t0 = System.nanoTime();
                try {
                    result = Formatter.format(dialect, text, FormatOptions.DEFAULTS);
                } catch (RuntimeException | StackOverflowError e) {
                    failures.add(name + ": exception " + e);
                    continue;
                }
                formatNanos += System.nanoTime() - t0;
                String out = result.text();
                if (result.changed()) {
                    changedFiles++;
                }
                skippedUnits += result.skipped().size();
                for (String warning : result.warnings()) {
                    System.out.println("  SKIP " + name + ": " + warning);
                    String reason = warning.replaceFirst("^Line \\d+: ", "").replaceAll("token \\d+ .*", "token changed")
                            .replaceAll("'.*'", "'…'");
                    reasons.merge(reason, 1, Integer::sum);
                }

                String tokens = sameUnits(dialect, text, out);
                if (tokens != null) {
                    failures.add(name + ": " + tokens);
                }
                units += frontend.splitter().split(SourceText.of(text)).size();

                String again = Formatter.format(dialect, out, FormatOptions.DEFAULTS).text();
                if (!again.equals(out)) {
                    failures.add(name + ": not idempotent at " + firstDifference(out, again));
                }

                FormatResult alt = Formatter.format(dialect, text, ALTERNATIVE);
                String altTokens = sameUnits(dialect, text, alt.text());
                if (altTokens != null) {
                    failures.add(name + " (alternative options): " + altTokens);
                }
                String altAgain = Formatter.format(dialect, alt.text(), ALTERNATIVE).text();
                if (!altAgain.equals(alt.text())) {
                    failures.add(name + ": not idempotent with alternative options at " + firstDifference(alt.text(), altAgain));
                }

                if (result.changed()) {
                    String parse = sameParse(pool, frontend, text, out);
                    if (parse == null) {
                        parseCompared++;
                    } else if (!parse.isEmpty()) {
                        failures.add(name + ": " + parse);
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }
        String summary = String.format("%-11s files=%d changed=%d units=%d skipped=%d parseCompared=%d lines=%d format=%dms %s",
                dialect.displayName(), files.size(), changedFiles, units, skippedUnits, parseCompared, lines,
                formatNanos / 1_000_000, reasons);
        REPORT.put(dialect, summary);
        System.out.println(summary);
        failures.forEach(f -> System.out.println("  FAIL " + f));
        assertTrue(failures.isEmpty(), dialect + ": " + failures.size() + " failures, first: "
                + (failures.isEmpty() ? "" : failures.get(0)));
    }

    /**
     * Same units (kind and tokens, keyword case aside) and same text between units apart from whitespace
     * ({@link Character#isWhitespace}, as the formatter's lexer defines it: U+2028 and U+0085 count too).
     */
    static String sameUnits(Dialect dialect, String in, String out) {
        DialectFrontend frontend = DialectFrontends.get(dialect);
        List<StatementUnit> a = frontend.splitter().split(SourceText.of(in));
        List<StatementUnit> b = frontend.splitter().split(SourceText.of(out));
        if (a.size() != b.size()) {
            return "unit count " + a.size() + " -> " + b.size();
        }
        int prevA = 0;
        int prevB = 0;
        for (int k = 0; k < a.size(); k++) {
            StatementUnit ua = a.get(k);
            StatementUnit ub = b.get(k);
            if (ua.kind() != ub.kind()) {
                return "unit " + k + " kind " + ua.kind() + " -> " + ub.kind();
            }
            String ta = slice(in, ua);
            String tb = slice(out, ub);
            String gapA = withoutWhitespace(in.substring(prevA, ua.range().start().offset()));
            String gapB = withoutWhitespace(out.substring(prevB, ub.range().start().offset()));
            if (!gapA.equals(gapB)) {
                return "text before unit " + k + " changed";
            }
            prevA = ua.range().end().offset();
            prevB = ub.range().end().offset();
            if (ua.kind() != UnitKind.SQL) {
                if (!ta.equals(tb)) {
                    return "client command " + k + " changed";
                }
                continue;
            }
            List<FormatToken> la = FormatLexer.lex(ta, dialect, ua.terminator());
            List<FormatToken> lb = FormatLexer.lex(tb, dialect, ub.terminator());
            if (la.size() != lb.size()) {
                return "unit at line " + ua.range().start().line() + " token count " + la.size() + " -> " + lb.size();
            }
            for (int j = 0; j < la.size(); j++) {
                FormatToken x = la.get(j);
                FormatToken y = lb.get(j);
                boolean same = x.kind == y.kind && (x.text.equals(y.text)
                        || (x.kind == FormatToken.Kind.WORD && Keywords.isCaseable(x.upper) && x.upper.equals(y.upper)));
                if (!same) {
                    return "unit at line " + ua.range().start().line() + " token " + x + " -> " + y;
                }
            }
        }
        String tailA = withoutWhitespace(in.substring(prevA));
        String tailB = withoutWhitespace(out.substring(prevB));
        return tailA.equals(tailB) ? null : "text after the last unit changed";
    }

    private static String withoutWhitespace(String s) {
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints().filter(c -> !Character.isWhitespace(c)).forEach(out::appendCodePoint);
        return out.toString();
    }

    /** null when both parse alike, "" when the comparison timed out, otherwise the difference. */
    private static String sameParse(ExecutorService pool, DialectFrontend frontend, String in, String out)
            throws Exception {
        Future<ScriptParse> before = pool.submit(() -> frontend.parse(SourceText.of(in)));
        ScriptParse pa;
        try {
            pa = before.get(PARSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            before.cancel(true);
            return "";
        }
        Future<ScriptParse> after = pool.submit(() -> frontend.parse(SourceText.of(out)));
        ScriptParse pb;
        try {
            pb = after.get(PARSE_TIMEOUT_SECONDS * 2, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            after.cancel(true);
            return "parse of the formatted text timed out";
        }
        if (pa.units().size() == pb.units().size()) {
            for (int k = 0; k < pa.units().size(); k++) {
                if (pa.units().get(k).status() != pb.units().get(k).status()) {
                    return "unit " + k + " parse " + pa.units().get(k).status() + " -> " + pb.units().get(k).status()
                            + " at line " + pa.units().get(k).unit().range().start().line() + " / formatted line "
                            + pb.units().get(k).unit().range().start().line();
                }
            }
        }
        if (pa.status() != pb.status() || pa.units().size() != pb.units().size()) {
            return "parse " + pa.status() + "/" + pa.units().size() + " -> " + pb.status() + "/" + pb.units().size();
        }
        return null;
    }

    private static String slice(String text, StatementUnit u) {
        return text.substring(u.range().start().offset(), u.range().end().offset());
    }

    private static String firstDifference(String a, String b) {
        int k = 0;
        while (k < a.length() && k < b.length() && a.charAt(k) == b.charAt(k)) {
            k++;
        }
        int from = Math.max(0, k - 60);
        return "offset " + k + ": [" + a.substring(from, Math.min(a.length(), k + 60)).replace("\n", "\\n") + "] vs ["
                + b.substring(from, Math.min(b.length(), k + 60)).replace("\n", "\\n") + "]";
    }

    @AfterAll
    static void printReport() throws IOException {
        System.out.println("Format corpus round trip:");
        REPORT.values().forEach(System.out::println);
    }
}
