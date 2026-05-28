package com.vocabtrainer.service;

import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.DictionaryLookupResult;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MockDictionaryService implements DictionaryService {
    private static final Map<String, DictionaryEntry> ENTRIES = Map.of(
        "abate", new DictionaryEntry("abate", "减弱; 减少", "verb", "/əˈbeɪt/",
            "The storm began to abate.", "Mock"),
        "candid", new DictionaryEntry("candid", "坦率的; 直言不讳的", "adjective", "/ˈkændɪd/",
            "She gave a candid answer.", "Mock"),
        "lucid", new DictionaryEntry("lucid", "清晰的; 明白易懂的", "adjective", "/ˈluːsɪd/",
            "The explanation was lucid.", "Mock"),
        "mitigate", new DictionaryEntry("mitigate", "减轻; 缓和", "verb", "/ˈmɪtɪɡeɪt/",
            "The policy may mitigate the risk.", "Mock")
    );

    @Override
    public DictionaryLookupResult lookup(String english) {
        String key = english == null ? "" : english.trim().toLowerCase(Locale.ROOT);
        if (key.isBlank()) {
            return DictionaryLookupResult.failure("Please enter an English word first.");
        }
        DictionaryEntry entry = ENTRIES.getOrDefault(key, new DictionaryEntry(
            english.trim(),
            "",
            "",
            "",
            "",
            "Mock fallback"
        ));
        if (entry.chinese().isBlank()) {
            return DictionaryLookupResult.failure("词条未找到：Mock 离线词典没有该词条。");
        }
        return DictionaryLookupResult.success("Using offline mock dictionary.", List.of(entry));
    }

    @Override
    public boolean isConfigured() {
        return true;
    }
}
