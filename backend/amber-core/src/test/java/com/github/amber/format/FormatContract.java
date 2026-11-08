package com.github.amber.format;

import com.github.amber.core.Dialect;

/**
 * The formatter's contract as one check, for tests outside this package: formatting keeps every unit's
 * tokens and comments (keyword case aside) and the text between units, and formatting the result again
 * changes nothing; formatting a selection keeps everything outside the selected statements.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
public final class FormatContract {

    private FormatContract() {
    }

    /** Null when the contract holds for {@code text}, otherwise what broke. */
    public static String check(Dialect dialect, String text, FormatOptions options) {
        FormatResult first = Formatter.format(dialect, text, options);
        String out = first.text();
        String units = FormatCorpusTest.sameUnits(dialect, text, out);
        if (units != null) {
            return "format changed more than layout: " + units;
        }
        String again = Formatter.format(dialect, out, options).text();
        if (!again.equals(out)) {
            return "format not idempotent at offset " + firstDifference(out, again);
        }
        return null;
    }

    /** Null when formatting {@code [start, end)} keeps the contract, otherwise what broke. */
    public static String checkRange(Dialect dialect, String text, int start, int end) {
        FormatResult r = Formatter.formatRange(dialect, text, start, end, FormatOptions.DEFAULTS);
        String units = FormatCorpusTest.sameUnits(dialect, text, r.text());
        return units == null ? null : "formatRange [" + start + ", " + end + ") changed more than layout: " + units;
    }

    private static int firstDifference(String a, String b) {
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            if (a.charAt(i) != b.charAt(i)) {
                return i;
            }
        }
        return n;
    }
}
