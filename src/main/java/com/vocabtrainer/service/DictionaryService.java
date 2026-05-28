package com.vocabtrainer.service;

import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.domain.WordVerificationResult;

public interface DictionaryService {
    DictionaryLookupResult lookup(String english);

    default WordVerificationResult verify(String english) {
        DictionaryLookupResult result = lookup(english);
        if (result.success() && !result.entries().isEmpty()) {
            return WordVerificationResult.found(result.entries().get(0).source(), result.message());
        }
        return WordVerificationResult.missing(result.message());
    }

    boolean isConfigured();
}
