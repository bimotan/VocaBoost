package com.vocabtrainer.domain;

import java.util.List;

public record DictionaryLookupResult(
    boolean success,
    String message,
    List<DictionaryEntry> entries
) {
    public static DictionaryLookupResult success(String message, List<DictionaryEntry> entries) {
        return new DictionaryLookupResult(true, message, List.copyOf(entries));
    }

    public static DictionaryLookupResult failure(String message) {
        return new DictionaryLookupResult(false, message, List.of());
    }
}
