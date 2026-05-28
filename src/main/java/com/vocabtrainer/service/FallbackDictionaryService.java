package com.vocabtrainer.service;

import com.vocabtrainer.domain.DictionaryLookupResult;

public class FallbackDictionaryService implements DictionaryService {
    private final DictionaryService primary;
    private final DictionaryService fallback;

    public FallbackDictionaryService(DictionaryService primary, DictionaryService fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public DictionaryLookupResult lookup(String english) {
        DictionaryLookupResult result = primary.lookup(english);
        if (result.success() && !result.entries().isEmpty()) {
            return result;
        }
        DictionaryLookupResult fallbackResult = fallback.lookup(english);
        if (fallbackResult.success() && !fallbackResult.entries().isEmpty()) {
            return DictionaryLookupResult.success(result.message() + " Fallback: " + fallbackResult.message(),
                fallbackResult.entries());
        }
        return DictionaryLookupResult.failure(result.message() + " Fallback: " + fallbackResult.message());
    }

    @Override
    public boolean isConfigured() {
        return primary.isConfigured();
    }
}
