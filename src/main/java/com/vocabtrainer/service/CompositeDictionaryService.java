package com.vocabtrainer.service;

import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.domain.WordVerificationResult;

import java.util.ArrayList;
import java.util.List;

public class CompositeDictionaryService implements DictionaryService {
    private final List<DictionaryService> services;

    public CompositeDictionaryService(List<DictionaryService> services) {
        this.services = List.copyOf(services);
    }

    @Override
    public DictionaryLookupResult lookup(String english) {
        List<String> messages = new ArrayList<>();
        for (DictionaryService service : services) {
            DictionaryLookupResult result = service.lookup(english);
            if (result.success() && !result.entries().isEmpty()) {
                return result;
            }
            messages.add(result.message());
        }
        return DictionaryLookupResult.failure(String.join(" | ", messages));
    }

    @Override
    public WordVerificationResult verify(String english) {
        List<String> messages = new ArrayList<>();
        for (DictionaryService service : services) {
            WordVerificationResult result = service.verify(english);
            if (result.found()) {
                return result;
            }
            messages.add(result.message());
        }
        return WordVerificationResult.missing(String.join(" | ", messages));
    }

    @Override
    public boolean isConfigured() {
        return services.stream().anyMatch(DictionaryService::isConfigured);
    }
}
