package com.github.amber.dialect.db2;

import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.split.BlockDepth;
import com.github.amber.dialect.split.ScanToken;
import com.github.amber.dialect.split.ScannerOptions;
import com.github.amber.dialect.split.ScriptSplitter;
import com.github.amber.dialect.split.SqlScanner;
import com.github.amber.dialect.split.StatementUnit;
import com.github.amber.dialect.split.UnitCollector;
import com.github.amber.dialect.split.UnitKind;

import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits Db2 CLP scripts:
 * <ul>
 *   <li>the statement terminator is {@code ;} unless changed by a {@code --#SET TERMINATOR x} directive,
 *       or detected as {@code @} when the script ends lines with {@code @} (the {@code db2 -td@} convention);</li>
 *   <li>with the {@code ;} terminator, {@code ;} inside {@code BEGIN … END} compound statements does not
 *       end the statement.</li>
 * </ul>
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class Db2Splitter implements ScriptSplitter {

    private static final Pattern DIRECTIVE = Pattern.compile("(?im)^\\s*--#SET\\s+TERMINATOR\\s+(\\S+)");

    @Override
    public List<StatementUnit> split(SourceText source) {
        String text = source.text();
        SqlScanner sc = new SqlScanner(text, ScannerOptions.DB2);
        UnitCollector units = new UnitCollector(source);

        TreeMap<Integer, String> directives = new TreeMap<>();
        Matcher m = DIRECTIVE.matcher(text);
        while (m.find()) {
            directives.put(m.start(), m.group(1));
        }
        String initial = directives.isEmpty() && endsLinesWithAt(text) ? "@" : ";";

        ScanToken first;
        while ((first = sc.next()) != null) {
            var directive = directives.floorEntry(first.start());
            String terminator = directive == null ? initial : directive.getValue();
            BlockDepth depth = new BlockDepth();
            int end = first.start();
            ScanToken t = first;
            while (t != null) {
                boolean atTerminator = text.startsWith(terminator, t.start()) && t.type() == ScanToken.Type.SYMBOL;
                if (atTerminator && !(terminator.equals(";") && depth.inBlock())) {
                    if (terminator.equals(";")) {
                        end = t.end();
                    }
                    sc.reset(t.start() + terminator.length());
                    break;
                }
                ScanToken next = sc.peek();
                depth.accept(t, next);
                end = t.end();
                t = sc.next();
            }
            units.add(UnitKind.SQL, first.start(), end);
        }
        return units.units();
    }

    /** True when some line ends with {@code @} outside strings and comments. */
    private static boolean endsLinesWithAt(String text) {
        SqlScanner sc = new SqlScanner(text, ScannerOptions.DB2);
        ScanToken t;
        while ((t = sc.next()) != null) {
            if (t.isSymbol('@') && sc.restOfLineEmpty(t.end())) {
                return true;
            }
        }
        return false;
    }
}
