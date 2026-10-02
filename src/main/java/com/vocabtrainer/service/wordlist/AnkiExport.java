package com.vocabtrainer.service.wordlist;

import java.util.ArrayList;
import java.util.List;

/**
 * The fields of "Export for Anki": a tab-separated Anki plain-text file whose header lines tell Anki
 * the separator, that the fields are HTML, the column names and which column holds the tags.
 * {@link WordListFile} reads such a file back into the same words, meanings and fields.
 */
public final class AnkiExport {
    /** The header lines, each ending in a line feed. */
    public static final String HEADER = "#separator:tab\n#html:true\n#columns:English\tChinese\tExample\tTags\n"
        + "#tags column:4\n";
    public static final char SEPARATOR = '\t';
    public static final String LINE_END = "\n";

    private AnkiExport() {
    }

    /**
     * The meaning as HTML, followed after a line break by the part of speech and the phonetic, each
     * in a span whose class names it: {@code 减弱; 减少<br><span class='pos'>verb</span>
     * <span class='phonetic'>/əˈbeɪt/</span>}. Anki shows them on a second line; the spans let the
     * import put them back into their fields. The attribute is in single quotes, so the field needs
     * no CSV quoting.
     */
    public static String meaningField(String chinese, String partOfSpeech, String phonetic) {
        StringBuilder field = new StringBuilder(HtmlText.escape(chinese));
        List<String> extras = new ArrayList<>();
        if (partOfSpeech != null && !partOfSpeech.isBlank()) {
            extras.add("<span class='pos'>" + HtmlText.escape(partOfSpeech) + "</span>");
        }
        if (phonetic != null && !phonetic.isBlank()) {
            extras.add("<span class='phonetic'>" + HtmlText.escape(phonetic) + "</span>");
        }
        if (!extras.isEmpty()) {
            field.append("<br>").append(String.join(" ", extras));
        }
        return field.toString();
    }

    /**
     * The tags as Anki writes them: separated by spaces. Anki tags cannot hold a space, so one inside
     * a tag ("word list") becomes an underscore ("word_list").
     */
    public static String tagsField(String tags) {
        if (tags == null || tags.isBlank()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (String tag : tags.split("[;,]")) {
            String name = tag.strip().replaceAll("\\s+", "_");
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return String.join(" ", names);
    }
}
