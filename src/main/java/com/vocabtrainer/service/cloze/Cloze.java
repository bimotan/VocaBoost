package com.vocabtrainer.service.cloze;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A cloze question: an example sentence with every occurrence of the word, also inflected, blanked
 * out; see {@link ClozeMaker#make}.
 *
 * @param spans the sentence, split into the blanked occurrences ({@link SentenceSpan#target()}) and
 *              the text around them; at least one is a target
 */
public record Cloze(List<SentenceSpan> spans) {
    /** What a blank looks like in {@link #masked()}. */
    public static final String BLANK = "_____";

    public Cloze {
        spans = List.copyOf(spans);
        if (spans.stream().noneMatch(SentenceSpan::target)) {
            throw new IllegalArgumentException("A cloze needs a blank");
        }
    }

    /** The whole sentence. */
    public String sentence() {
        StringBuilder text = new StringBuilder();
        spans.forEach(span -> text.append(span.text()));
        return text.toString();
    }

    /** The sentence with each occurrence of the word replaced by {@value #BLANK}. */
    public String masked() {
        StringBuilder text = new StringBuilder();
        spans.forEach(span -> text.append(span.target() ? BLANK : span.text()));
        return text.toString();
    }

    /**
     * The blanked words as the sentence has them, each once (ignoring case), in order, e.g.
     * "admonished"; typing any of them fills the blank.
     */
    public List<String> blankedForms() {
        Map<String, String> forms = new LinkedHashMap<>();
        for (SentenceSpan span : spans) {
            if (span.target()) {
                forms.putIfAbsent(span.text().toLowerCase(Locale.ROOT), span.text());
            }
        }
        return new ArrayList<>(forms.values());
    }
}
