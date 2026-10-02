package com.vocabtrainer.service;

import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.domain.WordVerificationResult;

/**
 * A dictionary that can look English words up. Lookups may use the network, so callers on the
 * JavaFX thread run them in the background. A lookup reports why it found nothing
 * ({@link com.vocabtrainer.domain.LookupOutcome}): a word the dictionary does not have is not the
 * same as a dictionary that could not be reached. An interrupted lookup returns
 * {@link DictionaryLookupResult#interrupted()} and keeps the thread's interrupt flag.
 */
public interface DictionaryService {
    DictionaryLookupResult lookup(String english);

    /** Looks the word up again, ignoring anything cached. */
    default DictionaryLookupResult refresh(String english) {
        return lookup(english);
    }

    /** Whether the dictionary has the word, with its phonetic when the dictionary gives one. */
    default WordVerificationResult verify(String english) {
        return WordVerificationResult.of(english, lookup(english));
    }

    boolean isConfigured();
}
