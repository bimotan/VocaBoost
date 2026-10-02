package com.vocabtrainer.service.wordlist;

import com.vocabtrainer.service.csv.WordColumn;
import com.vocabtrainer.service.csv.WordColumns;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Guesses which column of a word list without usable column names holds which word field, from the
 * text of its first rows: the English word, the Chinese meaning, the phonetic, the part of speech
 * and an example sentence. A column counts as one kind when at least half of its non-empty cells
 * look like it; the first such column gets the field. Columns that look like nothing in particular
 * keep the order of a GRE CSV without a header (english, chinese, pos, example, tags).
 */
final class ColumnDetector {
    /** The column order of a GRE CSV without a header row. */
    static final List<WordColumn> POSITIONAL_ORDER = List.of(
        WordColumn.ENGLISH, WordColumn.CHINESE, WordColumn.POS, WordColumn.EXAMPLE, WordColumn.TAGS);

    private static final String POS_ABBREVIATIONS =
        "n|v|vt|vi|a|adj|adv|ad|prep|conj|pron|int|interj|num|art|aux|abbr|phr|pl|det";
    private static final String POS_WORDS =
        "noun|verb|adjective|adverb|preposition|conjunction|pronoun|interjection|numeral|article|phrase|determiner"
            + "|transitive verb|intransitive verb|名词|动词|形容词|副词|介词|连词|代词|感叹词|数词|冠词|及物动词|不及物动词";
    private static final Pattern POS = Pattern.compile(
        "(?:(?:" + POS_ABBREVIATIONS + ")\\.?|" + POS_WORDS + ")(?:\\s*[,/&;、，]\\s*(?:(?:" + POS_ABBREVIATIONS
            + ")\\.?|" + POS_WORDS + "))*", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENGLISH_WORD = Pattern.compile("[A-Za-z][A-Za-z'\\-]*(?: [A-Za-z][A-Za-z'\\-]*){0,3}");
    /**
     * IPA anywhere, or slashes or brackets around a stress mark or length mark ("[ri:d]",
     * "/a'beit/"); a label such as "[formal]" or "[pl.]" is not a phonetic.
     */
    private static final Pattern PHONETIC = Pattern.compile(
        "[/\\[][^/\\[\\]]{0,60}['ˈˌ:ːəɪʊʌæɑɒɔθðʃʒŋɜ][^/\\[\\]]{0,60}[/\\]]|.*[ˈˌəɪʊʌæɑɒɔːθðʃʒŋɜ].*");
    private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-z]{2,}");
    private static final Pattern CJK = Pattern.compile("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]");

    private enum Kind { ENGLISH, CHINESE, PHONETIC, POS, SENTENCE, OTHER }

    private static final Map<Kind, WordColumn> FIELDS = Map.of(
        Kind.ENGLISH, WordColumn.ENGLISH, Kind.CHINESE, WordColumn.CHINESE, Kind.PHONETIC, WordColumn.PHONETIC,
        Kind.POS, WordColumn.POS, Kind.SENTENCE, WordColumn.EXAMPLE);

    private ColumnDetector() {
    }

    /**
     * Completes {@code known} (fields already found by name, which keep their columns) from the
     * text of {@code rows}, each row given as the text of its cells. {@code skipped} columns (Anki's
     * guid, deck and note type) and the columns {@code known} already reads get no field. With
     * {@code positionalFallback}, the remaining columns are read in the order of a GRE CSV without a
     * header, counted from the first column that is not skipped.
     */
    static WordColumns detect(WordColumns known, List<List<String>> rows, int width, Set<Integer> skipped,
                              boolean positionalFallback) {
        List<Kind> kinds = new ArrayList<>();
        for (int column = 0; column < width; column++) {
            kinds.add(kindOf(rows, column));
        }
        WordColumns columns = known;
        for (Kind kind : List.of(Kind.ENGLISH, Kind.CHINESE, Kind.PHONETIC, Kind.POS, Kind.SENTENCE)) {
            WordColumn field = FIELDS.get(kind);
            if (columns.has(field)) {
                continue;
            }
            for (int column = 0; column < width; column++) {
                if (kinds.get(column) == kind && free(columns, skipped, column)) {
                    columns = columns.with(field, column);
                    break;
                }
            }
            if (kind == Kind.ENGLISH && !columns.has(WordColumn.ENGLISH)) {
                // No column of single words (a list of longer phrases, say): the first free column
                // that holds no meaning, phonetic or part of speech is the word, as in a GRE CSV.
                for (int column = 0; column < width; column++) {
                    Kind found = kinds.get(column);
                    if (free(columns, skipped, column)
                        && (found == Kind.SENTENCE || found == Kind.OTHER || found == Kind.ENGLISH)) {
                        columns = columns.with(WordColumn.ENGLISH, column);
                        break;
                    }
                }
            }
        }
        if (!columns.has(WordColumn.ENGLISH)) {
            for (int column = 0; column < width; column++) {
                if (free(columns, skipped, column)) {
                    columns = columns.with(WordColumn.ENGLISH, column);
                    break;
                }
            }
        }
        if (positionalFallback) {
            int position = 0;
            for (int column = 0; column < width && position < POSITIONAL_ORDER.size(); column++) {
                if (skipped.contains(column)) {
                    continue;
                }
                WordColumn field = POSITIONAL_ORDER.get(position++);
                if (free(columns, skipped, column) && !columns.has(field)) {
                    columns = columns.with(field, column);
                }
            }
        }
        return columns;
    }

    private static boolean free(WordColumns columns, Set<Integer> skipped, int column) {
        return !skipped.contains(column) && columns.fieldAt(column).isEmpty();
    }

    /** The kind at least half of the column's non-empty cells have; OTHER when none does or all are empty. */
    private static Kind kindOf(List<List<String>> rows, int column) {
        Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
        int nonEmpty = 0;
        for (List<String> row : rows) {
            String cell = column < row.size() ? row.get(column).strip() : "";
            if (cell.isEmpty()) {
                continue;
            }
            nonEmpty++;
            counts.merge(kindOfCell(cell), 1, Integer::sum);
        }
        Kind best = Kind.OTHER;
        int bestCount = 0;
        for (Map.Entry<Kind, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return nonEmpty > 0 && bestCount * 2 >= nonEmpty ? best : Kind.OTHER;
    }

    /** True for a part of speech alone, such as "adj.", "n./v." or "verb". */
    static boolean isPartOfSpeech(String text) {
        return POS.matcher(text.strip()).matches();
    }

    /** True for a phonetic alone, such as "/əˈbeɪt/" or "[ə'beit]". */
    static boolean isPhonetic(String text) {
        String clean = text.strip();
        return PHONETIC.matcher(clean).matches() && !CJK.matcher(clean).find();
    }

    private static Kind kindOfCell(String cell) {
        if (isPartOfSpeech(cell)) {
            return Kind.POS;
        }
        if (isPhonetic(cell)) {
            return Kind.PHONETIC;
        }
        if (cell.length() <= 40 && ENGLISH_WORD.matcher(cell).matches()) {
            return Kind.ENGLISH;
        }
        if (latinWords(cell) >= 3) {
            return Kind.SENTENCE;
        }
        if (CJK.matcher(cell).find()) {
            return Kind.CHINESE;
        }
        return Kind.OTHER;
    }

    private static int latinWords(String cell) {
        int count = 0;
        var matcher = LATIN_WORD.matcher(cell);
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
