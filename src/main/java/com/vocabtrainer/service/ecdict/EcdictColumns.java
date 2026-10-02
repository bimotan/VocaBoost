package com.vocabtrainer.service.ecdict;

import com.vocabtrainer.domain.EcdictRow;
import com.vocabtrainer.service.csv.CsvFormatException;
import com.vocabtrainer.service.csv.CsvRecord;
import com.vocabtrainer.service.csv.FormulaGuard;
import com.vocabtrainer.service.csv.WordColumn;
import com.vocabtrainer.service.csv.WordColumns;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Which field of a dictionary CSV record holds which ECDICT column. The file can be ECDICT itself
 * (with or without its header row) or a word list such as the GRE CSVs.
 *
 * <p>The first record decides, see {@link #detect}:
 * <ul>
 *   <li>A header row is read by name: the word-list names of {@link WordColumn} (word/english/单词,
 *       translation/chinese/释义, phonetic, pos, example, tag) plus ECDICT's own definition,
 *       collins, oxford, bnc, frq and exchange.</li>
 *   <li>A first row whose first cell names the word column but whose other names are unknown
 *       ("Word,汉语解释") is a header too; the file is read as a word list by position.</li>
 *   <li>Without a header, five or more fields with Chinese in the fourth and none in the second are
 *       ECDICT's order (word, phonetic, definition, translation, pos, collins, oxford, tag, bnc,
 *       frq, exchange); anything else is a word list: english, chinese, pos, example, tags. So a
 *       headerless "lucid,清晰的,adjective,The explanation was lucid." keeps 清晰的 as the meaning.</li>
 * </ul>
 */
final class EcdictColumns {
    enum Field { WORD, PHONETIC, DEFINITION, TRANSLATION, POS, COLLINS, OXFORD, TAG, BNC, FRQ, EXCHANGE, EXAMPLE }

    private static final Field[] ECDICT_ORDER = {
        Field.WORD, Field.PHONETIC, Field.DEFINITION, Field.TRANSLATION, Field.POS, Field.COLLINS, Field.OXFORD,
        Field.TAG, Field.BNC, Field.FRQ, Field.EXCHANGE
    };
    private static final Field[] WORD_LIST_ORDER = {Field.WORD, Field.TRANSLATION, Field.POS, Field.EXAMPLE, Field.TAG};

    private final int[] indexes;
    private final String description;

    private EcdictColumns(int[] indexes, String layout) {
        this.indexes = indexes;
        this.description = describe(indexes) + " (" + layout + ")";
    }

    /**
     * How a file whose first non-blank record is {@code first} is read.
     *
     * @throws CsvFormatException when the first record is a header without a word or a translation column
     */
    static Detection detect(CsvRecord first) throws CsvFormatException {
        Optional<WordColumns> header = WordColumns.fromHeader(first);
        if (header.isPresent()) {
            return new Detection(fromHeader(first, header.get()), true);
        }
        if (WordColumns.startsWithEnglishColumnName(first)) {
            return new Detection(positional(WORD_LIST_ORDER, "header row with unknown names, read by position"), true);
        }
        if (looksLikeHeaderlessEcdict(first)) {
            return new Detection(positional(ECDICT_ORDER, "no header row, ECDICT column order"), false);
        }
        return new Detection(positional(WORD_LIST_ORDER, "no header row"), false);
    }

    /** @param headerRow whether the first record is a header, not an entry */
    record Detection(EcdictColumns columns, boolean headerRow) {
    }

    /** The mapped fields in file order and how they were found, e.g. "word, phonetic, ... (header row)". */
    String description() {
        return description;
    }

    String word(CsvRecord record) {
        return FormulaGuard.unprotect(get(record, Field.WORD)).strip();
    }

    String translation(CsvRecord record) {
        return FormulaGuard.unprotect(get(record, Field.TRANSLATION)).strip();
    }

    String exchange(CsvRecord record) {
        return get(record, Field.EXCHANGE).strip();
    }

    /** The record as a dictionary row; call only when {@link #word} and {@link #translation} are not blank. */
    EcdictRow row(CsvRecord record) {
        return new EcdictRow(
            word(record),
            get(record, Field.PHONETIC).strip(),
            get(record, Field.DEFINITION).strip(),
            translation(record),
            FormulaGuard.unprotect(get(record, Field.POS)).strip(),
            number(record, Field.COLLINS),
            number(record, Field.OXFORD),
            FormulaGuard.unprotect(get(record, Field.TAG)).strip(),
            number(record, Field.BNC),
            number(record, Field.FRQ),
            exchange(record),
            FormulaGuard.unprotect(get(record, Field.EXAMPLE)).strip()
        );
    }

    private String get(CsvRecord record, Field field) {
        return record.get(indexes[field.ordinal()]);
    }

    private Integer number(CsvRecord record, Field field) {
        String value = get(record, field).strip();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static EcdictColumns fromHeader(CsvRecord header, WordColumns names) throws CsvFormatException {
        int[] indexes = new int[Field.values().length];
        Arrays.fill(indexes, -1);
        indexes[Field.WORD.ordinal()] = names.index(WordColumn.ENGLISH);
        indexes[Field.TRANSLATION.ordinal()] = names.index(WordColumn.CHINESE);
        indexes[Field.PHONETIC.ordinal()] = names.index(WordColumn.PHONETIC);
        indexes[Field.POS.ordinal()] = names.index(WordColumn.POS);
        indexes[Field.EXAMPLE.ordinal()] = names.index(WordColumn.EXAMPLE);
        indexes[Field.TAG.ordinal()] = names.index(WordColumn.TAGS);
        for (int i = 0; i < header.size(); i++) {
            Field extra = switch (WordColumn.normalize(header.get(i))) {
                case "definition" -> Field.DEFINITION;
                case "collins" -> Field.COLLINS;
                case "oxford" -> Field.OXFORD;
                case "bnc" -> Field.BNC;
                case "frq" -> Field.FRQ;
                case "exchange" -> Field.EXCHANGE;
                default -> null;
            };
            // "definition" is the meaning only when there is no translation column.
            if (extra != null && indexes[extra.ordinal()] < 0 && i != indexes[Field.TRANSLATION.ordinal()]) {
                indexes[extra.ordinal()] = i;
            }
        }
        if (indexes[Field.WORD.ordinal()] < 0) {
            throw new CsvFormatException(header.lineNumber(),
                "the header row has no word column (word, english or 单词)");
        }
        if (indexes[Field.TRANSLATION.ordinal()] < 0) {
            throw new CsvFormatException(header.lineNumber(),
                "the header row has no Chinese meaning column (translation, chinese or 释义)");
        }
        return new EcdictColumns(indexes, "header row");
    }

    private static EcdictColumns positional(Field[] order, String layout) {
        int[] indexes = new int[Field.values().length];
        Arrays.fill(indexes, -1);
        for (int i = 0; i < order.length; i++) {
            indexes[order[i].ordinal()] = i;
        }
        return new EcdictColumns(indexes, layout);
    }

    private static boolean looksLikeHeaderlessEcdict(CsvRecord record) {
        return record.size() >= 5 && containsHan(record.get(3)) && !containsHan(record.get(1));
    }

    private static boolean containsHan(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private static String describe(int[] indexes) {
        List<Field> mapped = new ArrayList<>();
        for (Field field : Field.values()) {
            if (indexes[field.ordinal()] >= 0) {
                mapped.add(field);
            }
        }
        mapped.sort(Comparator.comparingInt(field -> indexes[field.ordinal()]));
        return String.join(", ", mapped.stream().map(field -> field.name().toLowerCase(java.util.Locale.ROOT)).toList());
    }
}
