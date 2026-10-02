package com.vocabtrainer.service.wordlist;

import com.vocabtrainer.service.csv.CsvFormatException;
import com.vocabtrainer.service.csv.CsvReader;
import com.vocabtrainer.service.csv.CsvRecord;
import com.vocabtrainer.service.csv.FormulaGuard;
import com.vocabtrainer.service.csv.TextEncoding;
import com.vocabtrainer.service.csv.WordColumn;
import com.vocabtrainer.service.csv.WordColumns;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A word list opened for import: a GRE CSV, a TSV, an Anki plain-text export, or a list of English
 * words, one per line. It reads the Anki header lines at the top ({@link AnkiHeader}), then the
 * records with {@link CsvReader} (Anki's separator, or sniffed), finds a header row, and keeps the
 * first {@value #SAMPLE_ROWS} data rows to detect the columns and label them for the preview.
 *
 * <p>{@link #fields} turns a record into word fields: the formula-guard apostrophe of our own CSV
 * export is removed, HTML is reduced to text ({@link HtmlText}) when the file says it is HTML (or,
 * without "#html:", when a cell contains HTML tags), sound and image references are dropped, line
 * breaks in a meaning separate meanings, and Anki's space-separated tags become "; "-separated ones.
 * The part of speech and phonetic that "Export for Anki" puts after the meaning are read back into
 * their own fields.
 */
public final class WordListFile implements Closeable {
    /** How many data rows are read ahead to detect and label the columns. */
    static final int SAMPLE_ROWS = 50;
    /** The fields of Anki's own note types, mapped when "#columns:" names them. */
    private static final Map<String, WordColumn> ANKI_FIELD_NAMES = Map.of(
        "front", WordColumn.ENGLISH, "back", WordColumn.CHINESE, "text", WordColumn.ENGLISH,
        "extra", WordColumn.NOTE, "backextra", WordColumn.NOTE);
    /** A line break in a meaning cell (Alt+Enter in Excel) separates two meanings. */
    private static final Pattern LINE_BREAKS = Pattern.compile("\\s*\\R\\s*");
    /** What "Export for Anki" appends to the meaning: {@code <span class='pos'>verb</span>}. */
    private static final Pattern EXPORTED_SPAN = Pattern.compile(
        "<span class=[\"']?(pos|phonetic)[\"']?>(.*?)</span>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final int LABEL_SAMPLE_LENGTH = 24;

    /** Where the column names come from. */
    public enum Layout {
        /** A header row names the columns. */
        HEADER_ROW,
        /** A header row names the English column only; it is skipped and the rest is detected. */
        SKIPPED_HEADER_ROW,
        /** Anki's "#columns:" line names the columns. */
        ANKI_COLUMNS,
        /** No names: the columns are detected from the content. */
        NO_HEADER
    }

    /** A column of the file, for the preview: its index, its name (may be empty) and a sample value. */
    public record FileColumn(int index, String name, String sample) {
        /** "Column 2 · Back: 减弱" */
        public String label() {
            StringBuilder label = new StringBuilder("Column ").append(index + 1);
            if (!name.isBlank()) {
                label.append(" · ").append(name);
            }
            if (!sample.isBlank()) {
                label.append(": ").append(sample);
            }
            return label.toString();
        }
    }

    private final CsvReader csv;
    private final AnkiHeader anki;
    private final Layout layout;
    private final CsvRecord headerRow;
    private final List<String> names;
    private final List<CsvRecord> sample = new ArrayList<>();
    private final Deque<CsvRecord> pending = new ArrayDeque<>();
    private final int width;

    private WordListFile(CsvReader csv, AnkiHeader anki) throws IOException {
        this.csv = csv;
        this.anki = anki;
        CsvRecord first = nextNonBlank();
        if (anki.hasColumnNames()) {
            layout = Layout.ANKI_COLUMNS;
            headerRow = null;
            names = anki.columnNames(csv.delimiter());
            keep(first);
        } else if (first != null && WordColumns.fromHeader(first).isPresent()) {
            layout = Layout.HEADER_ROW;
            headerRow = first;
            names = first.fields();
        } else if (first != null && WordColumns.startsWithEnglishColumnName(first)) {
            // A header such as "english,中文翻译": skipped rather than imported as the word "english".
            layout = Layout.SKIPPED_HEADER_ROW;
            headerRow = first;
            names = first.fields();
        } else {
            layout = Layout.NO_HEADER;
            headerRow = null;
            names = List.of();
            keep(first);
        }
        while (sample.size() < SAMPLE_ROWS) {
            CsvRecord record = nextNonBlank();
            if (record == null) {
                break;
            }
            keep(record);
        }
        int widest = names.size();
        for (CsvRecord record : sample) {
            widest = Math.max(widest, record.size());
        }
        width = widest;
    }

    /** Opens a file in the encoding {@link TextEncoding#detect} finds. */
    public static WordListFile open(Path path) throws IOException {
        TextEncoding encoding = TextEncoding.detect(path);
        BufferedReader reader = new BufferedReader(encoding.openReader(path));
        try {
            return open(reader, encoding);
        } catch (IOException | RuntimeException e) {
            reader.close();
            throw e;
        }
    }

    /** Reads a stream in a known charset, such as the bundled starter words. */
    public static WordListFile open(InputStream in, Charset charset) throws IOException {
        TextEncoding encoding = new TextEncoding(charset, 0);
        return open(new BufferedReader(encoding.openReader(in)), encoding);
    }

    private static WordListFile open(BufferedReader reader, TextEncoding encoding) throws IOException {
        AnkiHeader anki = readHeaderLines(reader, encoding);
        CsvReader csv = CsvReader.open(reader, encoding, anki.separator().orElse(null), anki.lineCount() + 1);
        return new WordListFile(csv, anki);
    }

    /**
     * Reads the lines at the top that start with '#' and leaves the reader at the first other line.
     * A byte order mark before the first line is skipped.
     */
    private static AnkiHeader readHeaderLines(BufferedReader reader, TextEncoding encoding) throws IOException {
        List<String> lines = new ArrayList<>();
        try {
            reader.mark(1);
            if (reader.read() != '﻿') {
                reader.reset();
            }
            while (true) {
                reader.mark(1);
                if (reader.read() != '#') {
                    reader.reset();
                    break;
                }
                String line = reader.readLine();
                lines.add(line == null ? "" : line);
            }
        } catch (CharacterCodingException e) {
            throw new CsvFormatException(lines.size() + 1, encoding.undecodableMessage(), e);
        }
        return AnkiHeader.parse(lines);
    }

    private void keep(CsvRecord record) {
        if (record != null) {
            sample.add(record);
            pending.addLast(record);
        }
    }

    private CsvRecord nextNonBlank() throws IOException {
        CsvRecord record = csv.read();
        while (record != null && record.isBlank()) {
            record = csv.read();
        }
        return record;
    }

    /** The next data record (blank ones included, as the file has them), or null at the end. */
    public CsvRecord next() throws IOException {
        return pending.isEmpty() ? csv.read() : pending.removeFirst();
    }

    public AnkiHeader anki() {
        return anki;
    }

    public Layout layout() {
        return layout;
    }

    /** The header row, when the file has one (also one that is only skipped). */
    public Optional<CsvRecord> headerRow() {
        return Optional.ofNullable(headerRow);
    }

    /** "UTF-8", "GBK/GB18030", ... */
    public String encodingName() {
        return csv.encoding().map(TextEncoding::displayName).orElse("decoded text");
    }

    /** "comma", "tab", ... */
    public String delimiterName() {
        return csv.delimiterName();
    }

    /** The number of columns: the most fields of the header and the first rows. */
    public int width() {
        return width;
    }

    /** Every column with its name and the first value of the first rows. */
    public List<FileColumn> columns() {
        List<FileColumn> columns = new ArrayList<>();
        for (int index = 0; index < width; index++) {
            String name = index < names.size() ? names.get(index).replace('﻿', ' ').strip() : "";
            Optional<String> role = anki.columnRole(index);
            if (role.isPresent()) {
                name = name.isEmpty() ? role.get() : name + " (" + role.get() + ")";
            }
            String value = "";
            for (CsvRecord record : sample) {
                String text = cellText(record.get(index));
                if (!text.isBlank()) {
                    value = shorten(text);
                    break;
                }
            }
            columns.add(new FileColumn(index, name, value));
        }
        return columns;
    }

    /**
     * The columns found without the user's help: by the header row's names (the header decides
     * alone), by Anki's "#columns:" names, and otherwise from the content of the first rows
     * ({@link ColumnDetector}). Anki's tags column is read as the tags; its guid, deck and note type
     * columns are never read.
     */
    public WordColumns detectColumns() {
        Set<Integer> skipped = anki.metadataColumns();
        WordColumns columns = switch (layout) {
            case HEADER_ROW -> WordColumns.fromHeader(headerRow).orElseThrow();
            case ANKI_COLUMNS -> WordColumns.byNames(names, ANKI_FIELD_NAMES);
            case SKIPPED_HEADER_ROW -> WordColumns.none().with(WordColumn.ENGLISH, 0);
            case NO_HEADER -> WordColumns.none();
        };
        for (WordColumn field : WordColumn.values()) {
            if (skipped.contains(columns.index(field))) {
                columns = columns.with(field, -1);
            }
        }
        if (anki.tagsColumn() >= 0) {
            columns = columns.with(WordColumn.TAGS, anki.tagsColumn());
        }
        boolean named = layout == Layout.HEADER_ROW
            || (layout == Layout.ANKI_COLUMNS && columns.has(WordColumn.ENGLISH));
        if (named) {
            return columns;
        }
        List<List<String>> rows = new ArrayList<>();
        for (CsvRecord record : sample) {
            List<String> cells = new ArrayList<>();
            for (String cell : record.fields()) {
                cells.add(cellText(cell));
            }
            rows.add(cells);
        }
        return ColumnDetector.detect(columns, rows, width, skipped, true);
    }

    /** Where the detected column names came from, for the preview, e.g. " (header row)". */
    public String describeLayout() {
        return switch (layout) {
            case HEADER_ROW -> " (header row)";
            case SKIPPED_HEADER_ROW -> " (header row line " + headerRow.lineNumber() + " skipped)";
            case ANKI_COLUMNS -> " (Anki #columns)";
            case NO_HEADER -> " (no header row)";
        };
    }

    /**
     * The text of every word field of {@code record} as {@code columns} map it; "" for a field that
     * is not mapped or a record that is too short. "#tags:" tags are added to every row's tags.
     */
    public Map<WordColumn, String> fields(CsvRecord record, WordColumns columns) {
        Map<WordColumn, String> fields = new EnumMap<>(WordColumn.class);
        for (WordColumn field : WordColumn.values()) {
            fields.put(field, "");
        }
        for (WordColumn field : WordColumn.values()) {
            if (!columns.has(field)) {
                continue;
            }
            String raw = columns.get(record, field);
            boolean html = isHtml(raw);
            if (field == WordColumn.CHINESE && html) {
                raw = moveExportedSpans(raw, columns, fields);
            }
            String text = HtmlText.toText(raw, html);
            if (field == WordColumn.CHINESE) {
                text = moveLoneParts(LINE_BREAKS.matcher(text).replaceAll("; "), columns, fields);
            } else if (field == WordColumn.TAGS && anki.isAnki()) {
                text = String.join("; ", splitTags(text));
            }
            if (!text.isEmpty() || fields.get(field).isEmpty()) {
                fields.put(field, text);
            }
        }
        if (!anki.tags().isEmpty()) {
            List<String> tags = new ArrayList<>(splitStoredTags(fields.get(WordColumn.TAGS)));
            for (String tag : anki.tags()) {
                if (tags.stream().noneMatch(tag::equalsIgnoreCase)) {
                    tags.add(tag);
                }
            }
            fields.put(WordColumn.TAGS, String.join("; ", tags));
        }
        return fields;
    }

    /**
     * Takes the part of speech and phonetic spans that "Export for Anki" writes after the meaning
     * out of a meaning cell, into their fields unless the file has its own columns for them.
     */
    private static String moveExportedSpans(String html, WordColumns columns, Map<WordColumn, String> fields) {
        Matcher matcher = EXPORTED_SPAN.matcher(html);
        StringBuilder rest = new StringBuilder();
        while (matcher.find()) {
            WordColumn field = matcher.group(1).equalsIgnoreCase("pos") ? WordColumn.POS : WordColumn.PHONETIC;
            if (!columns.has(field)) {
                fields.put(field, HtmlText.toText(matcher.group(2), true));
            }
            matcher.appendReplacement(rest, "");
        }
        matcher.appendTail(rest);
        return rest.toString();
    }

    /**
     * Takes a part of speech or a phonetic that stands alone among the meanings, as in Anki notes
     * whose back reads "减弱；减少<br>verb", into its field unless the file has its own column for it.
     */
    private static String moveLoneParts(String meaning, WordColumns columns, Map<WordColumn, String> fields) {
        if (!meaning.contains("; ")) {
            return meaning;
        }
        List<String> kept = new ArrayList<>();
        for (String part : meaning.split("; ")) {
            WordColumn field = ColumnDetector.isPartOfSpeech(part) ? WordColumn.POS
                : ColumnDetector.isPhonetic(part) ? WordColumn.PHONETIC
                : null;
            if (field != null && !columns.has(field) && fields.get(field).isEmpty()) {
                fields.put(field, part.strip());
            } else {
                kept.add(part);
            }
        }
        return kept.isEmpty() ? meaning : String.join("; ", kept);
    }

    private boolean isHtml(String cell) {
        return switch (anki.html()) {
            case HTML -> true;
            case TEXT -> false;
            case DETECT -> HtmlText.looksLikeHtml(cell);
        };
    }

    /** A cell as the preview and the column detection see it. */
    private String cellText(String cell) {
        String raw = FormulaGuard.unprotect(cell);
        return HtmlText.toText(raw, isHtml(raw));
    }

    private static List<String> splitTags(String text) {
        List<String> tags = new ArrayList<>();
        for (String tag : text.split("[\\s;,]+")) {
            if (!tag.isBlank()) {
                tags.add(tag);
            }
        }
        return tags;
    }

    private static List<String> splitStoredTags(String text) {
        List<String> tags = new ArrayList<>();
        for (String tag : text.split("[;,]")) {
            if (!tag.isBlank()) {
                tags.add(tag.strip());
            }
        }
        return tags;
    }

    private static String shorten(String text) {
        String line = text.replaceAll("\\s+", " ");
        return line.length() <= LABEL_SAMPLE_LENGTH ? line : line.substring(0, LABEL_SAMPLE_LENGTH - 1).stripTrailing() + "…";
    }

    @Override
    public void close() throws IOException {
        csv.close();
    }
}
