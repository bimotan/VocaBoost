package com.vocabtrainer.service.cloze;

import com.vocabtrainer.domain.WordCard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Finds an English word in a sentence, also in an inflected form: the word itself, the forms the
 * dictionary lists for it ({@link WordForms}, e.g. ECDICT's exchange field) and the forms simple
 * rules give ({@link Inflections}). Matching ignores case and only matches whole words: "abate"
 * is found in "Abating winds" but not in "abatement".
 *
 * <p>In an expression of several words ("give up", "red herring") the first or the last word may
 * be inflected ("gave up", "red herrings"), and the words may be separated by any spaces or a
 * hyphen.
 *
 * <p>The same matching marks the word in its example ({@link #highlight}) and blanks it out for a
 * cloze question ({@link #make}).
 */
public class ClozeMaker {
    private static final Pattern WORD_SEPARATOR = Pattern.compile("[\\s\\-\\u2010-\\u2015]+");
    /** Between the words of an expression in the sentence. */
    private static final String GAP = "[\\s\\-\\u2010-\\u2015]+";
    private static final String NOT_AFTER_LETTER = "(?<![\\p{L}\\p{N}])";
    private static final String NOT_BEFORE_LETTER = "(?![\\p{L}\\p{N}])";

    private final WordForms dictionary;

    /** @param dictionary the forms a dictionary lists for a word; {@link WordForms#NONE} for none */
    public ClozeMaker(WordForms dictionary) {
        this.dictionary = dictionary == null ? WordForms.NONE : dictionary;
    }

    /**
     * {@code sentence} split into the occurrences of {@code english} (and its forms) and the text
     * between them; one span that is not a target when the word does not occur, none for an empty
     * sentence.
     */
    public List<SentenceSpan> highlight(String sentence, String english) {
        String text = sentence == null ? "" : sentence.strip();
        if (text.isEmpty()) {
            return List.of();
        }
        Pattern pattern = patternOf(english);
        if (pattern == null) {
            return List.of(new SentenceSpan(text, false));
        }
        List<SentenceSpan> spans = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        int end = 0;
        while (matcher.find()) {
            if (matcher.start() > end) {
                spans.add(new SentenceSpan(text.substring(end, matcher.start()), false));
            }
            spans.add(new SentenceSpan(matcher.group(), true));
            end = matcher.end();
        }
        if (end < text.length()) {
            spans.add(new SentenceSpan(text.substring(end), false));
        }
        return List.copyOf(spans);
    }

    /** The cloze of the word's example sentence; see {@link #make(String, String)}. */
    public Optional<Cloze> make(WordCard word) {
        return make(word.getExampleSentence(), word.getEnglish());
    }

    /**
     * {@code sentence} with every occurrence of {@code english} blanked out; empty when there is no
     * sentence, the word does not occur in it, or nothing but the word is left (no context to fill
     * the blank from).
     */
    public Optional<Cloze> make(String sentence, String english) {
        List<SentenceSpan> spans = highlight(sentence, english);
        boolean hasBlank = spans.stream().anyMatch(SentenceSpan::target);
        boolean hasContext = spans.stream()
            .filter(span -> !span.target())
            .anyMatch(span -> span.text().codePoints().anyMatch(Character::isLetter));
        return hasBlank && hasContext ? Optional.of(new Cloze(spans)) : Optional.empty();
    }

    /**
     * The ways {@code english} may be written in a sentence, in lower case, each as its words: the
     * word and its forms, or for an expression the expression with its first or last word inflected.
     */
    List<List<String>> formsOf(String english) {
        List<String> words = words(english);
        if (words.isEmpty()) {
            return List.of();
        }
        Set<List<String>> forms = new LinkedHashSet<>();
        if (words.size() == 1) {
            formsOfWord(words.get(0)).forEach(form -> forms.add(List.of(form)));
        } else {
            forms.add(words);
            List<String> rest = words.subList(1, words.size());
            for (String first : formsOfWord(words.get(0))) {
                List<String> form = new ArrayList<>();
                form.add(first);
                form.addAll(rest);
                forms.add(List.copyOf(form));
            }
            List<String> init = words.subList(0, words.size() - 1);
            for (String last : formsOfWord(words.get(words.size() - 1))) {
                List<String> form = new ArrayList<>(init);
                form.add(last);
                forms.add(List.copyOf(form));
            }
            for (String listed : dictionary.of(String.join(" ", words))) {
                List<String> form = words(listed);
                if (!form.isEmpty()) {
                    forms.add(form);
                }
            }
        }
        return List.copyOf(forms);
    }

    private Set<String> formsOfWord(String word) {
        Set<String> forms = new LinkedHashSet<>(Inflections.of(word));
        for (String listed : dictionary.of(word)) {
            String form = listed == null ? "" : listed.strip().toLowerCase(Locale.ROOT);
            if (!form.isEmpty() && !WORD_SEPARATOR.matcher(form).find()) {
                forms.add(form);
            }
        }
        return forms;
    }

    /** Matches any form of {@code english} as whole words, the longest first; null for no word. */
    private Pattern patternOf(String english) {
        List<List<String>> forms = formsOf(english);
        if (forms.isEmpty()) {
            return null;
        }
        String alternatives = forms.stream()
            .map(form -> form.stream().map(Pattern::quote).collect(Collectors.joining(GAP)))
            .sorted(Comparator.comparingInt(String::length).reversed())
            .collect(Collectors.joining("|"));
        return Pattern.compile(NOT_AFTER_LETTER + "(?:" + alternatives + ")" + NOT_BEFORE_LETTER,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static List<String> words(String english) {
        String text = english == null ? "" : english.strip().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(WORD_SEPARATOR.split(text)).filter(word -> !word.isEmpty()).toList();
    }
}
