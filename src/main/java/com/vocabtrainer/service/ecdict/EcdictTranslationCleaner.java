package com.vocabtrainer.service.ecdict;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an ECDICT translation into a Chinese answer key a learner can type.
 *
 * <p>ECDICT keeps one line per part of speech, separated by a literal backslash-n, for example
 * {@code vt. 放弃, 抛弃, 遗弃, 使屈从, 沉溺, 放纵\nn. 放任, 无拘束, 狂热}. Lines tagged {@code [网络]}
 * (web usage), {@code [计]} (computing), {@code [医]} (medicine), {@code [法]} (law) and so on hold
 * specialist senses. Used as it is, the text makes the first sense of each line unmatchable
 * ("vt. 放弃" is not "放弃") and fills the meaning with jargon.
 *
 * <p>{@link #clean} splits the lines, moves their leading part-of-speech markers into the part of
 * speech, moves tagged lines and the "(abandonment 的复数)" remark of an inflected form's row into the
 * note (tagged lines stay when the word has nothing else), and joins the senses
 * with "; ". Senses are split at commas and semicolons, but not inside brackets or quotes, so
 * "使(马,鹰等)戴头罩" stays one sense. A meaning longer than {@value #MAX_MEANING_LENGTH} characters
 * keeps its first senses and lists the rest in the note.
 */
public final class EcdictTranslationCleaner {
    /** A longer meaning is cut at a sense boundary and the remaining senses go to the note. */
    static final int MAX_MEANING_LENGTH = 200;

    private static final Cleaned EMPTY = new Cleaned("", "", "");
    /** ECDICT writes line breaks as literal "\n" (sometimes "\r\n"); word lists may have real ones. */
    private static final Pattern LINE_BREAKS = Pattern.compile("\\\\r\\\\n|\\\\n|\\\\r|\\R");
    private static final String MARKER =
        "(vt|vi|v|n|adj|a|adv|ad|prep|conj|pron|interj|int|num|art|abbr|aux|suff|suf|pref|comb|pl|na)\\.";
    private static final Pattern LEADING_MARKERS = Pattern.compile("^(?:" + MARKER + "[\\s&,/]*)+");
    private static final Pattern ONE_MARKER = Pattern.compile(MARKER);
    private static final Pattern TAG = Pattern.compile("^\\[[^\\[\\]]{1,12}]\\s*");
    /** What ECDICT puts before the senses of an inflected form's own row: "(abandonment 的复数) n. 放弃, ...". */
    private static final Pattern INFLECTION_NOTE =
        Pattern.compile("^[(（]\\s*[A-Za-z][-A-Za-z'’. ]*?\\s*的[^()（）\\[\\]]{1,12}[)）]\\s*");
    private static final Map<String, String> PARTS_OF_SPEECH = Map.ofEntries(
        Map.entry("vt", "verb"), Map.entry("vi", "verb"), Map.entry("v", "verb"),
        Map.entry("n", "noun"),
        Map.entry("adj", "adjective"), Map.entry("a", "adjective"),
        Map.entry("adv", "adverb"), Map.entry("ad", "adverb"),
        Map.entry("prep", "preposition"), Map.entry("conj", "conjunction"), Map.entry("pron", "pronoun"),
        Map.entry("interj", "interjection"), Map.entry("int", "interjection"),
        Map.entry("num", "numeral"), Map.entry("art", "article"), Map.entry("abbr", "abbreviation"),
        Map.entry("aux", "auxiliary verb"), Map.entry("suff", "suffix"), Map.entry("suf", "suffix"),
        Map.entry("pref", "prefix"), Map.entry("comb", "combining form"), Map.entry("pl", "plural")
        // "na." (no part of speech given) is dropped without a name.
    );
    private static final String SEPARATORS = ",，;；";
    private static final String OPENING = "(（[【〔《“‘「<";
    private static final String CLOSING = ")）]】〕》”’」>";

    private EcdictTranslationCleaner() {
    }

    /**
     * The meaning, part of speech and note of an ECDICT translation.
     *
     * @param meaning      senses joined with "; "
     * @param partOfSpeech the parts of speech of the kept lines, such as "verb; noun"; empty when the
     *                     lines have no markers
     * @param note         an inflected form's remark such as "(abandonment 的复数)", the tagged lines and
     *                     any senses cut from a long meaning, one per line
     */
    public record Cleaned(String meaning, String partOfSpeech, String note) {
    }

    public static Cleaned clean(String translation) {
        if (translation == null || translation.isBlank()) {
            return EMPTY;
        }
        Set<String> senses = new LinkedHashSet<>();
        Set<String> partsOfSpeech = new LinkedHashSet<>();
        List<String> inflectionNotes = new ArrayList<>();
        List<String> taggedLines = new ArrayList<>();
        List<String> taggedSenses = new ArrayList<>();
        for (String rawLine : LINE_BREAKS.split(translation)) {
            String line = rawLine.strip();
            Matcher inflection = INFLECTION_NOTE.matcher(line);
            if (inflection.find()) {
                inflectionNotes.add(inflection.group().strip());
                line = line.substring(inflection.end());
            }
            if (line.isEmpty()) {
                continue;
            }
            Matcher markers = LEADING_MARKERS.matcher(line);
            String markerText = markers.find() ? markers.group() : "";
            String rest = line.substring(markerText.length()).strip();
            Matcher tag = TAG.matcher(rest);
            if (tag.find()) {
                taggedLines.add(line);
                taggedSenses.addAll(splitSenses(rest.substring(tag.end())));
                continue;
            }
            List<String> lineSenses = splitSenses(rest);
            if (!lineSenses.isEmpty()) {
                senses.addAll(lineSenses);
                Matcher marker = ONE_MARKER.matcher(markerText);
                while (marker.find()) {
                    String partOfSpeech = PARTS_OF_SPEECH.get(marker.group(1));
                    if (partOfSpeech != null) {
                        partsOfSpeech.add(partOfSpeech);
                    }
                }
            }
        }
        List<String> noteLines = new ArrayList<>(inflectionNotes);
        if (senses.isEmpty()) {
            // Only tagged senses, as for many technical terms and names: they are the meaning.
            senses.addAll(taggedSenses);
        } else {
            noteLines.addAll(taggedLines);
        }
        StringBuilder meaning = new StringBuilder();
        List<String> more = new ArrayList<>();
        for (String sense : senses) {
            if (meaning.length() == 0) {
                meaning.append(sense);
            } else if (more.isEmpty() && meaning.length() + 2 + sense.length() <= MAX_MEANING_LENGTH) {
                meaning.append("; ").append(sense);
            } else {
                more.add(sense);
            }
        }
        if (!more.isEmpty()) {
            noteLines.add("More meanings: " + String.join("; ", more));
        }
        return new Cleaned(meaning.toString(), String.join("; ", partsOfSpeech), String.join("\n", noteLines));
    }

    /** ECDICT's English definition with its literal "\n" separators turned into line breaks. */
    public static String unescapeLines(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (String line : LINE_BREAKS.split(text)) {
            if (!line.isBlank()) {
                lines.add(line.strip());
            }
        }
        return String.join("\n", lines);
    }

    /** Splits at commas and semicolons outside brackets and quotes; drops empty senses. */
    static List<String> splitSenses(String text) {
        List<String> senses = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (OPENING.indexOf(c) >= 0) {
                depth++;
            } else if (CLOSING.indexOf(c) >= 0 && depth > 0) {
                depth--;
            }
            if (depth == 0 && SEPARATORS.indexOf(c) >= 0) {
                addSense(senses, current);
            } else {
                current.append(c);
            }
        }
        addSense(senses, current);
        return senses;
    }

    private static void addSense(List<String> senses, StringBuilder current) {
        String sense = current.toString().strip();
        current.setLength(0);
        if (!sense.isEmpty()) {
            senses.add(sense);
        }
    }
}
