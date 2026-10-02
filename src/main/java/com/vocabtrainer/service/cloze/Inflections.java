package com.vocabtrainer.service.cloze;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The forms simple English inflection rules give a word: -s, -es, -ed, -d, -ing and -ly; -ies,
 * -ied and -ily for a consonant and y (decry: decries, decried); a dropped e before -ing (abate:
 * abating) and before -y for -le (subtle: subtly); a doubled final consonant before -ed and -ing
 * (abet: abetted, abetting); -ked, -king and -ally for -ic (panic: panicked, laconic: laconically).
 *
 * <p>Some forms are not words ("abatees"); that does no harm, since they are only looked for in the
 * word's own example sentence. Irregular forms come from the dictionary ({@link WordForms}).
 */
public final class Inflections {
    private static final String VOWELS = "aeiou";

    private Inflections() {
    }

    /** {@code word} and its inflected forms, in lower case. */
    public static Set<String> of(String word) {
        String base = word == null ? "" : word.strip().toLowerCase(Locale.ROOT);
        Set<String> forms = new LinkedHashSet<>();
        if (base.isEmpty()) {
            return forms;
        }
        forms.add(base);
        if (!isLetter(base, base.length() - 1)) {
            return Collections.unmodifiableSet(forms);
        }
        for (String ending : new String[] {"s", "es", "ed", "d", "ing", "ly"}) {
            forms.add(base + ending);
        }
        int length = base.length();
        if (base.endsWith("e") && length > 2) {
            String stem = base.substring(0, length - 1);
            forms.add(stem + "ing");
            if (base.endsWith("le")) {
                forms.add(stem + "y");
            }
            if (base.endsWith("ie")) {
                forms.add(base.substring(0, length - 2) + "ying");
            }
            if (base.endsWith("ue")) {
                forms.add(stem + "ly");
            }
        }
        if (length > 2 && base.endsWith("y") && isConsonant(base, length - 2)) {
            String stem = base.substring(0, length - 1);
            forms.add(stem + "ies");
            forms.add(stem + "ied");
            forms.add(stem + "ily");
        }
        if (base.endsWith("ic")) {
            forms.add(base + "ked");
            forms.add(base + "king");
            forms.add(base + "ally");
        }
        if (doublesFinalConsonant(base)) {
            char last = base.charAt(length - 1);
            forms.add(base + last + "ed");
            forms.add(base + last + "ing");
        }
        return Collections.unmodifiableSet(forms);
    }

    /**
     * Ends in consonant, vowel, consonant (other than w, x or y), as abet, defer and acquit do; a u
     * after q counts as a consonant. Whether the last syllable is stressed is not checked.
     */
    private static boolean doublesFinalConsonant(String word) {
        int length = word.length();
        if (length < 3) {
            return false;
        }
        char last = word.charAt(length - 1);
        boolean before = isConsonant(word, length - 3)
            || (word.charAt(length - 3) == 'u' && length > 3 && word.charAt(length - 4) == 'q');
        return isConsonant(word, length - 1) && "wxy".indexOf(last) < 0
            && isVowel(word, length - 2) && before;
    }

    private static boolean isLetter(String word, int index) {
        return Character.isLetter(word.charAt(index));
    }

    private static boolean isVowel(String word, int index) {
        return VOWELS.indexOf(word.charAt(index)) >= 0;
    }

    private static boolean isConsonant(String word, int index) {
        return isLetter(word, index) && !isVowel(word, index);
    }
}
