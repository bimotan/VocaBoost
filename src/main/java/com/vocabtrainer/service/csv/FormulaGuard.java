package com.vocabtrainer.service.csv;

import java.util.regex.Pattern;

/**
 * Keeps exported cells from running as spreadsheet formulas (OWASP "CSV injection").
 *
 * <p>Excel, WPS and LibreOffice treat a cell that starts with {@code = + - @}, a tab or a carriage
 * return as a formula, even when the CSV field is quoted, so a gloss such as
 * {@code =HYPERLINK("http://x","点击")} would become a live link. {@link #protect} puts an apostrophe
 * in front of such a cell; spreadsheets then show it as text (some show the apostrophe too).
 * {@link #unprotect} removes that apostrophe when the file is imported again, so "-ness" exports as
 * "'-ness" and imports as "-ness".
 *
 * <p>The round trip is exact: a cell that already starts with apostrophes followed by one of those
 * characters gets one more apostrophe, and import removes exactly one. Plain numbers such as
 * {@code -3} or {@code +1.5} cannot run anything and are left alone.
 */
public final class FormulaGuard {
    // A leading line feed is guarded too: CsvReader returns a carriage return inside a field as one.
    private static final Pattern NEEDS_GUARD = Pattern.compile("'*[=+\\-@\\t\\r\\n]");
    private static final Pattern GUARDED = Pattern.compile("'+[=+\\-@\\t\\r\\n]");
    private static final Pattern PLAIN_NUMBER = Pattern.compile("[+-]?\\d+(\\.\\d+)?");

    private FormulaGuard() {
    }

    public static String protect(String value) {
        if (value == null || value.isEmpty() || "=+-@\t\r\n'".indexOf(value.charAt(0)) < 0
            || !NEEDS_GUARD.matcher(value).lookingAt() || PLAIN_NUMBER.matcher(value).matches()) {
            return value;
        }
        return "'" + value;
    }

    public static String unprotect(String value) {
        // Checked first because the dictionary loader calls this for every field of a large file.
        if (value == null || value.isEmpty() || value.charAt(0) != '\'' || !GUARDED.matcher(value).lookingAt()) {
            return value;
        }
        return value.substring(1);
    }
}
