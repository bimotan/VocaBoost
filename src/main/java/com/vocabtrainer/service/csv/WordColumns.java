package com.vocabtrainer.service.csv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
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
