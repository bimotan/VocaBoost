package com.vocabtrainer.service.csv;

import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Writes CSV for spreadsheets: comma-separated, CRLF line ends, a field quoted only when it holds
 * the delimiter, a quote, a line break or leading/trailing spaces, and every cell passed through
 * {@link FormulaGuard#protect}. {@link CsvReader} reads it back unchanged.
 */
public final class CsvWriter implements Closeable {
    private static final String LINE_END = "\r\n";

    private final Writer out;
    private final char delimiter;
    private final String lineEnd;
    private final boolean guardFormulas;

    public CsvWriter(Writer out, char delimiter) {
        this(out, delimiter, LINE_END, true);
    }

    private CsvWriter(Writer out, char delimiter, String lineEnd, boolean guardFormulas) {
        this.out = out;
        this.delimiter = delimiter;
        this.lineEnd = lineEnd;
        this.guardFormulas = guardFormulas;
    }

    /**
     * Writes records for an app that is not a spreadsheet, such as Anki: quoted where needed, but
     * without {@link FormulaGuard}, which would put an apostrophe into the app's fields.
     */
    public static CsvWriter plainText(Writer out, char delimiter, String lineEnd) {
        return new CsvWriter(out, delimiter, lineEnd, false);
    }

    /**
     * Creates or replaces a UTF-8 file that starts with a byte order mark, without which Excel on
     * Chinese Windows reads UTF-8 as GBK and shows the Chinese text garbled.
     */
    public static CsvWriter create(Path path) throws IOException {
        Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        try {
            writer.write('\uFEFF');
        } catch (IOException e) {
            writer.close();
            throw e;
        }
        return new CsvWriter(writer, ',');
    }

    public void writeRow(String... cells) throws IOException {
        writeRow(Arrays.asList(cells));
    }

    public void writeRow(List<String> cells) throws IOException {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.write(delimiter);
            }
            String cell = cells.get(i) == null ? "" : cells.get(i);
            out.write(guardFormulas ? encode(cell, delimiter) : quote(cell, delimiter));
        }
        out.write(lineEnd);
    }

    /** One cell as it appears in the file; null is written as an empty cell. */
    static String encode(String value, char delimiter) {
        return quote(FormulaGuard.protect(value == null ? "" : value), delimiter);
    }

    private static String quote(String cell, char delimiter) {
        if (!needsQuotes(cell, delimiter)) {
            return cell;
        }
        return "\"" + cell.replace("\"", "\"\"") + "\"";
    }

    private static boolean needsQuotes(String cell, char delimiter) {
        if (cell.isEmpty()) {
            return false;
        }
        if (Character.isWhitespace(cell.charAt(0)) || Character.isWhitespace(cell.charAt(cell.length() - 1))) {
            return true;
        }
        for (int i = 0; i < cell.length(); i++) {
            char c = cell.charAt(i);
            if (c == delimiter || c == '"' || c == '\n' || c == '\r') {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
