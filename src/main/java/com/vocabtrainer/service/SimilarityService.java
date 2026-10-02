package com.vocabtrainer.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Compares a typed answer with the correct one, with one comparator per question direction.
 *
 * <p><b>Meanings</b> (English to Chinese, {@link #calculate}). A gloss is split into meanings at
 * ; , / 、 and line breaks (a literal {@code \n} too, as ECDICT writes them), at spaces between
 * two Chinese characters and before a part-of-speech marker (also one written right after a Chinese
 * character, as in {@code n.放弃v.抛弃}), but never inside brackets. Each
 * meaning drops leading part-of-speech markers ({@code vt.}, {@code adj.}, several in a row),
 * bracket tags such as {@code [网络]} or {@code 【医】}, punctuation and spaces; full-width
 * characters count as half-width and case is ignored. A trailing 的, 地 or 得 is ignored when at
 * least two characters remain, and a parenthesized part is optional: {@code (使)减弱} matches both
 * 使减弱 and 减弱. The typed answer is split the same way, so several meanings can be typed (it
 * also counts as a whole without its spaces, so 清 晰 is 清晰); every typed meaning is compared
 * with every correct one and the best pair counts. A pair that is equal scores 1.0, any other the
 * mean of their character-bigram Dice coefficient and Levenshtein similarity.
 *
 * <p><b>English words</b> (Chinese to English, {@link #englishSimilarity}). Case, accents, spaces
 * and hyphens are ignored; otherwise only letters are compared, by Damerau-Levenshtein distance
 * ({@link #spellingDistance}). Whether a near miss is a typo or another word is decided by
 * {@link AnswerGrader}.
 */
public class SimilarityService {
    private static final Pattern LITERAL_LINE_BREAK = Pattern.compile("\\\\[nr]");
    private static final String PART_OF_SPEECH = "(?:vt|vi|v|n|adj|adv|ad|a|prep|conj|pron|int|num|art|abbr)\\.";
    private static final Pattern PART_OF_SPEECH_PREFIX =
        Pattern.compile("^(?:" + PART_OF_SPEECH + "\\s*(?:[&/]\\s*)?)+", Pattern.CASE_INSENSITIVE);
    /** A part-of-speech marker that starts a new meaning after a space: "放弃 v.抛弃" and "放弃 v. 抛弃", not "5 a.m.". */
    private static final Pattern PART_OF_SPEECH_AHEAD =
        Pattern.compile("^" + PART_OF_SPEECH + "(?:\\s|$|(?=[^\\p{IsLatin}]))", Pattern.CASE_INSENSITIVE);
    /** A part-of-speech marker written right after a Chinese character and before the next meaning: "放弃v.抛弃". */
    private static final Pattern PART_OF_SPEECH_GLUED =
        Pattern.compile("^" + PART_OF_SPEECH + "\\s*(?=\\p{IsHan})", Pattern.CASE_INSENSITIVE);
    private static final Pattern BRACKET_TAG = Pattern.compile("\\[[^\\]]*\\]|【[^】]*】");
    private static final Pattern OPTIONAL_PART = Pattern.compile("\\([^()]*\\)");
    private static final Pattern PUNCTUATION_AND_SPACE = Pattern.compile("[\\p{Punct}\\p{IsPunctuation}\\s]+");
    private static final Pattern SPACE_OR_HYPHEN = Pattern.compile("[\\s\\-\\u2010-\\u2015]+");
    private static final Pattern ACCENT = Pattern.compile("\\p{M}+");
    private static final Pattern NOT_A_LETTER = Pattern.compile("\\P{L}+");
    private static final Pattern SPACE_BETWEEN_HAN = Pattern.compile("(?<=\\p{IsHan})\\s+(?=\\p{IsHan})");
    private static final String MEANING_SEPARATORS = ";,/、\n\r";
    private static final String SUFFIXES = "的地得";

    /**
     * How similar a typed meaning is to the gloss {@code correctAnswer}, from 0 to 1: 1.0 when a
     * typed meaning equals one of the gloss's meanings; see the class comment. Two blank answers
     * are equal, a blank one against a gloss scores 0.
     */
    public double calculate(String userAnswer, String correctAnswer) {
        List<Set<String>> typed = new ArrayList<>(meaningVariants(userAnswer));
        typed.addAll(meaningVariants(SPACE_BETWEEN_HAN.matcher(prepare(userAnswer)).replaceAll("")));
        List<Set<String>> correct = meaningVariants(correctAnswer);
        if (typed.isEmpty() && correct.isEmpty()) {
            return 1.0;
        }
        double best = 0.0;
        for (Set<String> typedMeaning : typed) {
            for (Set<String> correctMeaning : correct) {
                for (String left : typedMeaning) {
                    for (String right : correctMeaning) {
                        best = Math.max(best, pairSimilarity(left, right));
                        if (best >= 1.0) {
                            return 1.0;
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * The meanings of a gloss in the form {@link #calculate} compares them, each with and without
     * its optional parts; two glosses share a meaning when these sets intersect.
     */
    public Set<String> meaningKeys(String gloss) {
        Set<String> keys = new LinkedHashSet<>();
        meaningVariants(gloss).forEach(keys::addAll);
        return keys;
    }

    /**
     * The meanings of a gloss, cleaned for display and comparison but not yet normalized: part of
     * speech markers and bracket tags removed, e.g. {@code [vt. 放弃, 抛弃\nn. 放任]} gives
     * {@code [放弃, 抛弃, 放任]}.
     */
    public List<String> splitMeanings(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> meanings = new ArrayList<>();
        for (String part : splitOutsideBrackets(prepare(value))) {
            String meaning = cleanMeaning(part);
            if (!meaning.isEmpty()) {
                meanings.add(meaning);
            }
        }
        return meanings;
    }

    /**
     * How similar a typed English word is to {@code expected}, from 0 to 1: 1.0 when they are equal
     * apart from case, accents, spaces and hyphens, otherwise 1 - d / n, where d is the
     * {@link #spellingDistance} and n the length of the longer one in letters.
     */
    public double englishSimilarity(String typed, String expected) {
        if (sameEnglish(typed, expected)) {
            return 1.0;
        }
        String left = letters(typed);
        String right = letters(expected);
        int longer = Math.max(left.codePointCount(0, left.length()), right.codePointCount(0, right.length()));
        if (longer == 0) {
            return 0.0;
        }
        return Math.max(0.0, 1.0 - spellingDistance(typed, expected) / (double) longer);
    }

    /** Whether two English answers are the same apart from case, accents, spaces and hyphens. */
    public boolean sameEnglish(String typed, String expected) {
        return normalizeEnglish(typed).equals(normalizeEnglish(expected));
    }

    /**
     * How many letters must be inserted, deleted, replaced or swapped with a neighbour to turn the
     * letters of {@code typed} into those of {@code expected} (Damerau-Levenshtein, optimal string
     * alignment); everything but letters is ignored, and case and accents too.
     */
    public int spellingDistance(String typed, String expected) {
        return damerauLevenshtein(letters(typed).codePoints().toArray(), letters(expected).codePoints().toArray());
    }

    /** The letters of an English answer, lower case and without accents; what {@link #spellingDistance} compares. */
    public String letters(String value) {
        return NOT_A_LETTER.matcher(withoutAccents(prepare(value))).replaceAll("");
    }

    /** Lower case, half-width, without punctuation or spaces; what equal meanings have in common. */
    public String normalize(String value) {
        if (value == null) {
            return "";
        }
        return PUNCTUATION_AND_SPACE.matcher(prepare(value)).replaceAll("");
    }

    private String normalizeEnglish(String value) {
        return SPACE_OR_HYPHEN.matcher(withoutAccents(prepare(value)).trim()).replaceAll("");
    }

    /** Drops accents from Latin letters, so naive is naïve and cafe is café. */
    private static String withoutAccents(String value) {
        return ACCENT.matcher(Normalizer.normalize(value, Normalizer.Form.NFD)).replaceAll("");
    }

    /** Half-width (NFKC), lower case, with ECDICT's literal line breaks turned into real ones. */
    private static String prepare(String value) {
        if (value == null) {
            return "";
        }
        String halfWidth = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return LITERAL_LINE_BREAK.matcher(halfWidth).replaceAll("\n");
    }

    /** Each meaning of {@code gloss} as its normalized variants (with and without optional parts). */
    private List<Set<String>> meaningVariants(String gloss) {
        List<Set<String>> meanings = new ArrayList<>();
        for (String meaning : splitMeanings(gloss)) {
            Set<String> variants = new LinkedHashSet<>();
            addVariant(variants, OPTIONAL_PART.matcher(meaning).replaceAll(""));
            addVariant(variants, meaning.replace("(", "").replace(")", ""));
            if (!variants.isEmpty()) {
                meanings.add(variants);
            }
        }
        return meanings;
    }

    private void addVariant(Set<String> variants, String meaning) {
        String normalized = withoutSuffix(normalize(meaning));
        if (!normalized.isEmpty()) {
            variants.add(normalized);
        }
    }

    /** Drops a trailing 的, 地 or 得 when at least two characters remain: 清晰的 is 清晰, 获得 stays. */
    private static String withoutSuffix(String meaning) {
        int length = meaning.codePointCount(0, meaning.length());
        if (length >= 3 && SUFFIXES.indexOf(meaning.charAt(meaning.length() - 1)) >= 0) {
            return meaning.substring(0, meaning.length() - 1);
        }
        return meaning;
    }

    private static String cleanMeaning(String part) {
        String meaning = BRACKET_TAG.matcher(part).replaceAll(" ").trim();
        meaning = PART_OF_SPEECH_PREFIX.matcher(meaning).replaceFirst("").trim();
        return meaning;
    }

    /**
     * Splits at meaning separators, at spaces between two Chinese characters, at spaces before a
     * part-of-speech marker and before one that directly follows a Chinese character, but not inside
     * (), [] or 【】.
     */
    private static List<String> splitOutsideBrackets(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            int next = index + Character.charCount(codePoint);
            if (codePoint == '(' || codePoint == '[' || codePoint == '【') {
                depth++;
            } else if ((codePoint == ')' || codePoint == ']' || codePoint == '】') && depth > 0) {
                depth--;
            }
            if (depth == 0 && MEANING_SEPARATORS.indexOf(codePoint) >= 0) {
                cut(parts, current);
            } else if (depth == 0 && endsWithHan(current) && isLatinLetter(codePoint)
                && PART_OF_SPEECH_GLUED.matcher(text).region(index, text.length()).lookingAt()) {
                cut(parts, current);
                current.appendCodePoint(codePoint);
            } else if (depth == 0 && Character.isWhitespace(codePoint)) {
                int end = next;
                while (end < text.length() && Character.isWhitespace(text.codePointAt(end))) {
                    end += Character.charCount(text.codePointAt(end));
                }
                if (end < text.length() && startsNewMeaning(current, text, end)) {
                    cut(parts, current);
                } else {
                    current.append(' ');
                }
                next = end;
            } else {
                current.appendCodePoint(codePoint);
            }
            index = next;
        }
        cut(parts, current);
        return parts;
    }

    private static boolean startsNewMeaning(StringBuilder before, String text, int start) {
        if (before.isEmpty()) {
            return false;
        }
        int previous = before.codePointBefore(before.length());
        if (isHan(previous) && isHan(text.codePointAt(start))) {
            return true;
        }
        return PART_OF_SPEECH_AHEAD.matcher(text.substring(start)).lookingAt();
    }

    private static boolean isLatinLetter(int codePoint) {
        return Character.isLetter(codePoint) && Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN;
    }

    private static boolean endsWithHan(StringBuilder text) {
        return !text.isEmpty() && isHan(text.codePointBefore(text.length()));
    }

    private static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private static void cut(List<String> parts, StringBuilder current) {
        String part = current.toString().trim();
        if (!part.isEmpty()) {
            parts.add(part);
        }
        current.setLength(0);
    }

    private static double pairSimilarity(String left, String right) {
        if (left.equals(right)) {
            return 1.0;
        }
        int[] leftPoints = left.codePoints().toArray();
        int[] rightPoints = right.codePoints().toArray();
        return 0.5 * bigramDice(leftPoints, rightPoints) + 0.5 * levenshteinSimilarity(leftPoints, rightPoints);
    }

    /** Dice coefficient of the character bigrams; a single character is its own "bigram". */
    private static double bigramDice(int[] left, int[] right) {
        Set<Long> leftBigrams = bigrams(left);
        Set<Long> rightBigrams = bigrams(right);
        if (leftBigrams.isEmpty() || rightBigrams.isEmpty()) {
            return 0.0;
        }
        Set<Long> shared = new HashSet<>(leftBigrams);
        shared.retainAll(rightBigrams);
        return 2.0 * shared.size() / (leftBigrams.size() + rightBigrams.size());
    }

    private static Set<Long> bigrams(int[] points) {
        Set<Long> bigrams = new HashSet<>();
        if (points.length == 1) {
            bigrams.add((long) points[0]);
        }
        for (int i = 0; i + 1 < points.length; i++) {
            bigrams.add(((long) points[i] << 32) | (points[i + 1] & 0xffffffffL));
        }
        return bigrams;
    }

    private static double levenshteinSimilarity(int[] left, int[] right) {
        int maxLength = Math.max(left.length, right.length);
        if (maxLength == 0) {
            return 1.0;
        }
        return Math.max(0.0, 1.0 - damerauLevenshtein(left, right, false) / (double) maxLength);
    }

    private static int damerauLevenshtein(int[] left, int[] right) {
        return damerauLevenshtein(left, right, true);
    }

    /** Edit distance; with {@code transpositions}, swapping two neighbours counts as one edit. */
    private static int damerauLevenshtein(int[] left, int[] right, boolean transpositions) {
        int[][] distance = new int[left.length + 1][right.length + 1];
        for (int i = 0; i <= left.length; i++) {
            distance[i][0] = i;
        }
        for (int j = 0; j <= right.length; j++) {
            distance[0][j] = j;
        }
        for (int i = 1; i <= left.length; i++) {
            for (int j = 1; j <= right.length; j++) {
                int substitution = left[i - 1] == right[j - 1] ? 0 : 1;
                int best = Math.min(Math.min(distance[i - 1][j] + 1, distance[i][j - 1] + 1),
                    distance[i - 1][j - 1] + substitution);
                if (transpositions && i > 1 && j > 1 && left[i - 1] == right[j - 2] && left[i - 2] == right[j - 1]) {
                    best = Math.min(best, distance[i - 2][j - 2] + 1);
                }
                distance[i][j] = best;
            }
        }
        return distance[left.length][right.length];
    }
}
