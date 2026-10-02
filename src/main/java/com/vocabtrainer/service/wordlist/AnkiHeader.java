package com.vocabtrainer.service.wordlist;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The header lines of an Anki plain-text file ("Notes in Plain Text", Anki 2.1.55 and later): the
 * lines at the top of the file that start with '#', such as
 *
 * <pre>
 * #separator:tab
 * #html:true
 * #columns:Front	Back	Tags
 * #tags column:3
 * </pre>
 *
 * Recognised keys are {@code separator} (tab, comma, semicolon, space, pipe, colon, or the character
 * itself), {@code html}, {@code columns}, {@code tags} (tags for every note) and the {@code tags},
 * {@code deck}, {@code notetype} and {@code guid} columns. Other '#' lines at the top of the file,
 * such as {@code #deck:GRE} or a comment, are skipped. Column numbers are 1-based in the file and
 * 0-based here.
 */
public final class AnkiHeader {
    /** How field text is read: as HTML, as plain text, or as HTML only when it contains HTML tags. */
    public enum Html { HTML, TEXT, DETECT }

    /** A file without header lines. */
    public static final AnkiHeader NONE = new AnkiHeader(0, null, Html.DETECT, null, -1, -1, -1, -1, List.of(), Set.of());

    private static final Map<String, Character> SEPARATORS = Map.of(
        "tab", '\t', "comma", ',', "semicolon", ';', "space", ' ', "pipe", '|', "colon", ':');

    private final int lineCount;
    private final Character separator;
    private final Html html;
    private final String columns;
    private final int tagsColumn;
    private final int deckColumn;
    private final int notetypeColumn;
    private final int guidColumn;
    private final List<String> tags;
    private final Set<String> keys;

    private AnkiHeader(int lineCount, Character separator, Html html, String columns, int tagsColumn, int deckColumn,
                       int notetypeColumn, int guidColumn, List<String> tags, Set<String> keys) {
        this.lineCount = lineCount;
        this.separator = separator;
        this.html = html;
        this.columns = columns;
        this.tagsColumn = tagsColumn;
        this.deckColumn = deckColumn;
        this.notetypeColumn = notetypeColumn;
        this.guidColumn = guidColumn;
        this.tags = List.copyOf(tags);
        this.keys = Set.copyOf(keys);
    }

    /** Parses header lines without their leading '#'. */
    public static AnkiHeader parse(List<String> lines) {
        Parser parser = new Parser();
        lines.forEach(parser::accept);
        return parser.build();
    }

    /** How many lines at the top of the file are header lines, recognised or not. */
    public int lineCount() {
        return lineCount;
    }

    /** True when at least one line was a recognised Anki header. */
    public boolean isAnki() {
        return !keys.isEmpty();
    }

    /** The separator the file names; empty when it names none, so it is sniffed. */
    public Optional<Character> separator() {
        return Optional.ofNullable(separator);
    }

    public Html html() {
        return html;
    }

    /** The names of "#columns:", split at {@code delimiter}; empty when the file has no such line. */
    public List<String> columnNames(char delimiter) {
        if (columns == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= columns.length(); i++) {
            if (i == columns.length() || columns.charAt(i) == delimiter) {
                names.add(columns.substring(start, i).strip());
                start = i + 1;
            }
        }
        return names;
    }

    public boolean hasColumnNames() {
        return columns != null;
    }

    /** The column that holds the note's tags, or -1. */
    public int tagsColumn() {
        return tagsColumn;
    }

    /**
     * The columns that hold no note field: deck, note type and guid. The tags column is not one of
     * them; it is read as the word's tags.
     */
    public Set<Integer> metadataColumns() {
        Set<Integer> columns = new LinkedHashSet<>();
        for (int column : new int[] {deckColumn, notetypeColumn, guidColumn}) {
            if (column >= 0) {
                columns.add(column);
            }
        }
        return columns;
    }

    /** What a column holds by the header, for labels: "guid", "deck", "note type", "tags" or empty. */
    public Optional<String> columnRole(int column) {
        if (column < 0) {
            return Optional.empty();
        }
        if (column == guidColumn) {
            return Optional.of("Anki guid");
        }
        if (column == notetypeColumn) {
            return Optional.of("Anki note type");
        }
        if (column == deckColumn) {
            return Optional.of("Anki deck");
        }
        if (column == tagsColumn) {
            return Optional.of("Anki tags");
        }
        return Optional.empty();
    }

    /** The tags "#tags:" gives every note. */
    public List<String> tags() {
        return tags;
    }

    /** For the preview, e.g. "Anki headers: separator, html, columns, tags column". */
    public String describe() {
        return isAnki() ? "Anki headers: " + String.join(", ", keys.stream().sorted().toList()) : "";
    }

    private static final class Parser {
        private int lineCount;
        private Character separator;
        private Html html = Html.DETECT;
        private String columns;
        private int tagsColumn = -1;
        private int deckColumn = -1;
        private int notetypeColumn = -1;
        private int guidColumn = -1;
        private final List<String> tags = new ArrayList<>();
        private final Set<String> keys = new LinkedHashSet<>();

        void accept(String line) {
            lineCount++;
            int colon = line.indexOf(':');
            if (colon < 0) {
                return;
            }
            String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1);
            String trimmed = value.strip();
            switch (key) {
                case "separator" -> separator(value).ifPresent(found -> {
                    separator = found;
                    keys.add(key);
                });
                case "html" -> {
                    if (trimmed.equalsIgnoreCase("true") || trimmed.equalsIgnoreCase("false")) {
                        html = trimmed.equalsIgnoreCase("true") ? Html.HTML : Html.TEXT;
                        keys.add(key);
                    }
                }
                case "columns" -> {
                    columns = value;
                    keys.add(key);
                }
                case "tags" -> {
                    for (String tag : trimmed.split("\\s+")) {
                        if (!tag.isEmpty()) {
                            tags.add(tag);
                        }
                    }
                    keys.add(key);
                }
                case "tags column" -> tagsColumn = column(trimmed, key, tagsColumn);
                case "deck column" -> deckColumn = column(trimmed, key, deckColumn);
                case "notetype column" -> notetypeColumn = column(trimmed, key, notetypeColumn);
                case "guid column" -> guidColumn = column(trimmed, key, guidColumn);
                case "deck", "notetype", "if matches" -> keys.add(key);
                default -> {
                    // A comment or a header of a newer Anki: skipped.
                }
            }
        }

        private int column(String value, String key, int previous) {
            try {
                int number = Integer.parseInt(value);
                if (number >= 1) {
                    keys.add(key);
                    return number - 1;
                }
            } catch (NumberFormatException e) {
                // Not a column number; the line is skipped.
            }
            return previous;
        }

        /** Anki's separator names, or the separator itself when the value is one character. */
        private static Optional<Character> separator(String value) {
            Character named = SEPARATORS.get(value.strip().toLowerCase(Locale.ROOT));
            if (named != null) {
                return Optional.of(named);
            }
            String withoutLineEnd = value.replace("\r", "").replace("\n", "");
            if (withoutLineEnd.length() == 1 && withoutLineEnd.charAt(0) != '"') {
                return Optional.of(withoutLineEnd.charAt(0));
            }
            if (withoutLineEnd.strip().length() == 1 && withoutLineEnd.strip().charAt(0) != '"') {
                return Optional.of(withoutLineEnd.strip().charAt(0));
            }
            return Optional.empty();
        }

        AnkiHeader build() {
            return new AnkiHeader(lineCount, separator, html, columns, tagsColumn, deckColumn, notetypeColumn,
                guidColumn, tags, keys);
        }
    }
}
