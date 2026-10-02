package com.vocabtrainer.service.cloze;

import java.util.Set;

/**
 * The inflected forms a dictionary lists for an English word, such as the past tense and plural in
 * ECDICT's exchange field. {@link ClozeMaker} adds them to the forms simple rules give
 * ({@link Inflections}), so irregular forms ("forwent" for forgo) are found too.
 */
@FunctionalInterface
public interface WordForms {
    /** No dictionary: only the forms of {@link Inflections} are found. */
    WordForms NONE = english -> Set.of();

    /** The forms listed for {@code english}; empty when the dictionary does not have the word. */
    Set<String> of(String english);
}
