package com.vocabtrainer.service.csv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Which field of a word-list CSV record holds which word field, by header names or by position. */
public final class WordColumns {
    /** The columns "Export words CSV" writes, in order; its header row uses their header names. */
    public static final List<WordColumn> EXPORT_ORDER = List.of(WordColumn.values());

    private final int[] indexes;
    private final boolean fromHeader;

    private WordColumns(int[] indexes, boolean fromHeader) {
        this.indexes = indexes;
        this.fromHeader = fromHeader;
    }

    /**
     * Reads a header row, or returns empty when {@code record} looks like data. It is a header when
     * its cells name at least two different word fields, or when its only cell names one. (Two
     * names of the same field, as in a "note,笔记" data row, do not count.)
     */
    public static Optional<WordColumns> fromHeader(CsvRecord record) {
        int[] indexes = new int[WordColumn.values().length];
        int[] ranks = new int[indexes.length];
        Arrays.fill(indexes, -1);
        Arrays.fill(ranks, Integer.MAX_VALUE);
        Set<WordColumn> named = EnumSet.noneOf(WordColumn.class);
        int nonBlankCells = 0;
        for (int i = 0; i < record.size(); i++) {
            String cell = record.get(i);
            if (cell.isBlank()) {
                continue;
            }
            nonBlankCells++;
            WordColumn.Alias alias = WordColumn.match(cell);
            if (alias == null) {
                continue;
            }
            named.add(alias.column());
            int column = alias.column().ordinal();
            if (alias.rank() < ranks[column]) {
                indexes[column] = i;
                ranks[column] = alias.rank();
            }
        }
        boolean header = nonBlankCells == 1 ? named.size() == 1 : named.size() >= 2;
        return header ? Optional.of(new WordColumns(indexes, true)) : Optional.empty();
    }

    /**
     * True when a first row that {@link #fromHeader} does not take as a header is still one: its
     * first cell names the English column, as in "english,中文翻译" or "Word,Meaning (中文)", so the
     * row is a header whose other names are unknown. Such a file is read by position after skipping
     * the row. A row such as "word,单词" names the English column twice, so it is the word "word"
     * and its meaning, not a header.
     */
    public static boolean startsWithEnglishColumnName(CsvRecord record) {
        if (!namesEnglish(record.get(0))) {
            return false;
        }
        for (int i = 1; i < record.size(); i++) {
            if (namesEnglish(record.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean namesEnglish(String cell) {
        WordColumn.Alias alias = WordColumn.match(cell);
        return alias != null && alias.column() == WordColumn.ENGLISH;
    }

    /**
     * Columns by position, for a file without a header row: the first column is {@code order[0]},
     * and a null entry is a column that is not read.
     */
    public static WordColumns positional(WordColumn... order) {
        int[] indexes = new int[WordColumn.values().length];
        Arrays.fill(indexes, -1);
        for (int i = 0; i < order.length; i++) {
            if (order[i] != null) {
                indexes[order[i].ordinal()] = i;
            }
        }
        return new WordColumns(indexes, false);
    }

    /**
     * Columns by name, such as the names of an Anki file's "#columns:" line: each name is matched like
     * a header cell, and {@code extraAliases} (normalized names, see {@link WordColumn#normalize})
     * add names that only count here. Unknown names are not read. Unlike {@link #fromHeader}, any
     * number of known names is enough.
     */
    public static WordColumns byNames(List<String> names, Map<String, WordColumn> extraAliases) {
        int[] indexes = new int[WordColumn.values().length];
        int[] ranks = new int[indexes.length];
        Arrays.fill(indexes, -1);
        Arrays.fill(ranks, Integer.MAX_VALUE);
        for (int i = 0; i < names.size(); i++) {
            WordColumn.Alias alias = WordColumn.match(names.get(i));
            if (alias == null) {
                WordColumn extra = extraAliases.get(WordColumn.normalize(names.get(i)));
                // Ranked after every built-in alias.
                alias = extra == null ? null : new WordColumn.Alias(extra, Integer.MAX_VALUE - 1);
            }
            if (alias != null && alias.rank() < ranks[alias.column().ordinal()]) {
                indexes[alias.column().ordinal()] = i;
                ranks[alias.column().ordinal()] = alias.rank();
            }
        }
        return new WordColumns(indexes, true);
    }

    /** No column read at all; add them with {@link #with}. */
    public static WordColumns none() {
        int[] indexes = new int[WordColumn.values().length];
        Arrays.fill(indexes, -1);
        return new WordColumns(indexes, false);
    }

    /** These columns with {@code column} read from field {@code index} instead; -1 stops reading it. */
    public WordColumns with(WordColumn column, int index) {
        if (index < -1) {
            throw new IllegalArgumentException("Column index " + index);
        }
        int[] changed = indexes.clone();
        changed[column.ordinal()] = index;
        return new WordColumns(changed, fromHeader);
    }

    /** The word field read from field {@code index}, if any; the first in {@link WordColumn} order. */
    public Optional<WordColumn> fieldAt(int index) {
        for (WordColumn column : WordColumn.values()) {
            if (indexes[column.ordinal()] == index && index >= 0) {
                return Optional.of(column);
            }
        }
        return Optional.empty();
    }

    /** One more than the highest field index read; 0 when nothing is read. */
    public int width() {
        int width = 0;
        for (int index : indexes) {
            width = Math.max(width, index + 1);
        }
        return width;
    }

    public boolean fromHeader() {
        return fromHeader;
    }

    public boolean has(WordColumn column) {
        return indexes[column.ordinal()] >= 0;
    }

    /** The field's index in a record, or -1 when the file has no such column. */
    public int index(WordColumn column) {
        return indexes[column.ordinal()];
    }

    /**
     * The field's value in {@code record} with any {@link FormulaGuard} apostrophe removed; "" when
     * the file has no such column or the record is shorter.
     */
    public String get(CsvRecord record, WordColumn column) {
        return FormulaGuard.unprotect(record.get(index(column)));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WordColumns columns && Arrays.equals(indexes, columns.indexes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(indexes);
    }

    @Override
    public String toString() {
        return describe();
    }

    /** The mapped fields in file order, e.g. "english, chinese, pos, example, tags". */
    public String describe() {
        List<WordColumn> byPosition = new ArrayList<>();
        for (WordColumn column : WordColumn.values()) {
            if (has(column)) {
                byPosition.add(column);
            }
        }
        byPosition.sort((left, right) -> Integer.compare(index(left), index(right)));
        return String.join(", ", byPosition.stream().map(WordColumn::headerName).toList());
    }
}
