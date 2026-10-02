package com.vocabtrainer.service.csv;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads CSV records one at a time (RFC 4180), so a large file is never held in memory.
 *
 * <ul>
 *   <li>Fields may be quoted; a quoted field can hold the delimiter, line breaks and doubled quotes
 *       ({@code ""}). A quote inside an unquoted field is an ordinary character.</li>
 *   <li>Line breaks may be CRLF, LF or CR; line breaks inside a field are returned as LF.</li>
 *   <li>A leading byte order mark is skipped and empty lines are ignored.</li>
 *   <li>Unless a delimiter is given, it is chosen from comma, tab and semicolon by looking at the
 *       first records: the one that splits them into the same number (at least two) of fields.</li>
 * </ul>
 */
public final class CsvReader implements Closeable {
    /**
     * The delimiters that are sniffed, in order of preference when two fit equally well. Tab comes
     * first: a tab-separated list whose meanings each hold one comma ("abate\t减弱, 减少") splits
     * into two fields either way, and a tab inside a CSV field is far rarer than a comma inside a
     * TSV field.
     */
    private static final char[] DELIMITERS = {'\t', ',', ';'};
    private static final char DEFAULT_DELIMITER = ',';
    /** A longer field almost always means a quote that is never closed. */
    static final int MAX_FIELD_LENGTH = 1 << 20;
    private static final int BUFFER_LENGTH = 64 * 1024;
    private static final int SAMPLE_RECORDS = 20;
    private static final int END = -1;

    private enum State { FIELD_START, UNQUOTED, QUOTED, QUOTE_IN_QUOTED }

    private final Reader in;
    private final TextEncoding encoding;
    private final char delimiter;
    private final char[] buffer = new char[BUFFER_LENGTH];
    private final StringBuilder field = new StringBuilder();
    private int position;
    private int limit;
    private boolean endOfInput;
    private boolean started;
    private int line = 1;

    private CsvReader(Reader in, TextEncoding encoding, Character delimiter) throws IOException {
        this.in = in;
        this.encoding = encoding;
        if (delimiter != null) {
            this.delimiter = delimiter;
        } else {
            boolean wholeText = fillSample();
            this.delimiter = sniffDelimiter(new String(buffer, 0, limit), wholeText);
        }
    }

    /** Opens a file in the encoding {@link TextEncoding#detect(Path)} finds, with a sniffed delimiter. */
    public static CsvReader open(Path path) throws IOException {
        TextEncoding encoding = TextEncoding.detect(path);
        Reader reader = encoding.openReader(path);
        try {
            return new CsvReader(reader, encoding, null);
        } catch (IOException | RuntimeException e) {
            reader.close();
            throw e;
        }
    }

    /**
     * Reads a stream from its first byte in an encoding {@link TextEncoding#detect(Path)} found, with
     * a sniffed delimiter; the byte order mark is skipped. For callers that count the bytes read.
     */
    public static CsvReader open(InputStream in, TextEncoding encoding) throws IOException {
        in.skipNBytes(encoding.bomLength());
        return new CsvReader(encoding.openReader(in), encoding, null);
    }

    /** Reads a stream in a known charset, with a sniffed delimiter. */
    public static CsvReader open(InputStream in, Charset charset) throws IOException {
        TextEncoding encoding = new TextEncoding(charset, 0);
        return new CsvReader(encoding.openReader(in), encoding, null);
    }

    /** Reads text that is already decoded, with a sniffed delimiter. */
    public static CsvReader open(Reader reader) throws IOException {
        return new CsvReader(reader, null, null);
    }

    public static CsvReader open(Reader reader, char delimiter) throws IOException {
        return new CsvReader(reader, null, delimiter);
    }

    /** The encoding the file is read in; empty when the caller passed decoded text. */
    public Optional<TextEncoding> encoding() {
        return Optional.ofNullable(encoding);
    }

    public char delimiter() {
        return delimiter;
    }

    /** "comma", "tab" or "semicolon", for messages. */
    public String delimiterName() {
        return switch (delimiter) {
            case ',' -> "comma";
            case '\t' -> "tab";
            case ';' -> "semicolon";
            default -> "'" + delimiter + "'";
        };
    }

    /** The next record, or null at the end of the text. */
    public CsvRecord read() throws IOException {
        skipByteOrderMark();
        while (true) {
            int startLine = line;
            int c = next();
            if (c == END) {
                return null;
            }
            if (c == '\n') {
                continue;
            }
            List<String> fields = new ArrayList<>();
            readFields(c, startLine, fields);
            return new CsvRecord(fields, startLine);
        }
    }

    private void readFields(int first, int startLine, List<String> fields) throws IOException {
        field.setLength(0);
        State state = State.FIELD_START;
        int c = first;
        while (true) {
            if (c == END) {
                if (state == State.QUOTED) {
                    throw new CsvFormatException(startLine, "a quoted field is not closed (a \" is missing)");
                }
                fields.add(takeField());
                return;
            }
            switch (state) {
                case FIELD_START -> {
                    if (c == '"') {
                        state = State.QUOTED;
                    } else if (c == delimiter) {
                        fields.add(takeField());
                    } else if (c == '\n') {
                        fields.add(takeField());
                        return;
                    } else {
                        append(c, startLine);
                        state = State.UNQUOTED;
                    }
                }
                case UNQUOTED -> {
                    if (c == delimiter) {
                        fields.add(takeField());
                        state = State.FIELD_START;
                    } else if (c == '\n') {
                        fields.add(takeField());
                        return;
                    } else if (c == '"' && field.toString().isBlank()) {
                        // Spaces before an opening quote, as in: abate, "减弱, 减少"
                        field.setLength(0);
                        state = State.QUOTED;
                    } else {
                        append(c, startLine);
                    }
                }
                case QUOTED -> {
                    if (c == '"') {
                        state = State.QUOTE_IN_QUOTED;
                    } else {
                        append(c, startLine);
                    }
                }
                case QUOTE_IN_QUOTED -> {
                    if (c == '"') {
                        append('"', startLine);
                        state = State.QUOTED;
                    } else if (c == delimiter) {
                        fields.add(takeField());
                        state = State.FIELD_START;
                    } else if (c == '\n') {
                        fields.add(takeField());
                        return;
                    } else {
                        // Text after the closing quote is kept, as spreadsheets do.
                        append(c, startLine);
                        state = State.UNQUOTED;
                    }
                }
            }
            c = next();
        }
    }

    private void append(int c, int startLine) throws CsvFormatException {
        if (field.length() >= MAX_FIELD_LENGTH) {
            throw new CsvFormatException(startLine, "a field is longer than " + MAX_FIELD_LENGTH
                + " characters; is a closing \" missing?");
        }
        field.append((char) c);
    }

    private String takeField() {
        String value = field.toString();
        field.setLength(0);
        return value;
    }

    /** The next character with CRLF and CR turned into LF, counting lines; {@link #END} at the end. */
    private int next() throws IOException {
        if (position >= limit && !fill()) {
            return END;
        }
        char c = buffer[position++];
        if (c == '\r') {
            if ((position < limit || fill()) && buffer[position] == '\n') {
                position++;
            }
            c = '\n';
        }
        if (c == '\n') {
            line++;
        }
        return c;
    }

    private void skipByteOrderMark() throws IOException {
        if (!started) {
            started = true;
            if ((position < limit || fill()) && buffer[position] == '\uFEFF') {
                position++;
            }
        }
    }

    private boolean fill() throws IOException {
        if (endOfInput) {
            return false;
        }
        int count;
        try {
            count = in.read(buffer, 0, buffer.length);
        } catch (CharacterCodingException e) {
            String problem = encoding == null
                ? "the text is not valid in the expected encoding (save the file as UTF-8 and try again)"
                : encoding.undecodableMessage();
            throw new CsvFormatException(line, problem, e);
        }
        if (count < 0) {
            endOfInput = true;
            return false;
        }
        position = 0;
        limit = count;
        return true;
    }

    /** Fills the buffer as far as possible; true when it then holds the whole text. */
    private boolean fillSample() throws IOException {
        while (limit < buffer.length) {
            int count;
            try {
                count = in.read(buffer, limit, buffer.length - limit);
            } catch (CharacterCodingException e) {
                // Reported with its line number when the records are read.
                return false;
            }
            if (count < 0) {
                endOfInput = true;
                return true;
            }
            limit += count;
        }
        return false;
    }

    /**
     * The delimiter that splits the sample's first records most consistently into two or more
     * fields; comma when none does (for example in a one-column word list).
     */
    static char sniffDelimiter(String sample, boolean wholeText) {
        char best = DEFAULT_DELIMITER;
        int bestMatching = 0;
        int bestTotal = 1;
        int bestWidth = 1;
        for (char candidate : DELIMITERS) {
            List<Integer> widths = sampleWidths(sample, wholeText, candidate);
            if (widths.isEmpty()) {
                continue;
            }
            Map<Integer, Integer> frequency = new HashMap<>();
            widths.forEach(width -> frequency.merge(width, 1, Integer::sum));
            int width = 1;
            int matching = 0;
            for (Map.Entry<Integer, Integer> entry : frequency.entrySet()) {
                if (entry.getValue() > matching || (entry.getValue() == matching && entry.getKey() > width)) {
                    width = entry.getKey();
                    matching = entry.getValue();
                }
            }
            if (width < 2) {
                continue;
            }
            // Compare matching/total as fractions, then prefer more fields.
            long better = (long) matching * bestTotal - (long) bestMatching * widths.size();
            if (better > 0 || (better == 0 && width > bestWidth)) {
                best = candidate;
                bestMatching = matching;
                bestTotal = widths.size();
                bestWidth = width;
            }
        }
        return best;
    }

    private static List<Integer> sampleWidths(String sample, boolean wholeText, char candidate) {
        List<Integer> widths = new ArrayList<>();
        boolean reachedEnd = false;
        try (CsvReader reader = new CsvReader(new StringReader(sample), null, candidate)) {
            while (widths.size() < SAMPLE_RECORDS) {
                CsvRecord record = reader.read();
                if (record == null) {
                    reachedEnd = true;
                    break;
                }
                if (!record.isBlank()) {
                    widths.add(record.size());
                }
            }
        } catch (IOException e) {
            // A quoted field cut off at the end of the sample; the records before it still count.
            return widths;
        }
        if (reachedEnd && !wholeText && widths.size() > 1) {
            // The sample may end in the middle of the last record.
            widths.remove(widths.size() - 1);
        }
        return widths;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
