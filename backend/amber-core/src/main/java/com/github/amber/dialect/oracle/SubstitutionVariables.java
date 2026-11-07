package com.github.amber.dialect.oracle;

import com.github.amber.dialect.split.ScanToken;
import com.github.amber.dialect.split.ScannerOptions;
import com.github.amber.dialect.split.SqlScanner;

/**
 * SQL*Plus substitution variables ({@code &name}, {@code &&name}, {@code &1}, {@code &name.}) are
 * replaced by the client before Oracle sees the SQL, so the grammar does not know them. This class
 * masks them into plain identifiers of the same length ({@code &start_day} → {@code Xstart_day}) so the
 * statement parses and every offset still matches the original text. Text inside strings and
 * comments is left alone.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class SubstitutionVariables {

    private SubstitutionVariables() {
    }

    static String mask(String text) {
        if (text.indexOf('&') < 0) {
            return text;
        }
        char[] out = text.toCharArray();
        SqlScanner sc = new SqlScanner(text, ScannerOptions.ORACLE);
        ScanToken t;
        while ((t = sc.next()) != null) {
            if (!t.isSymbol('&')) {
                continue;
            }
            int i = t.start();
            while (i < out.length && out[i] == '&') {
                out[i++] = 'X';
            }
            int nameEnd = i;
            while (nameEnd < out.length && (Character.isLetterOrDigit(out[nameEnd]) || out[nameEnd] == '_'
                    || out[nameEnd] == '$' || out[nameEnd] == '#')) {
                nameEnd++;
            }
            if (nameEnd == i) {
                continue;                       // a lone '&' is not a variable; leave it for the parser
            }
            if (nameEnd < out.length && out[nameEnd] == '.') {
                out[nameEnd] = ' ';             // '.' only terminates the variable name
            }
            sc.reset(nameEnd);
        }
        return new String(out);
    }
}
