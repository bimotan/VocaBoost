package com.vocabtrainer.service;

import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.domain.LookupOutcome;
import com.vocabtrainer.domain.WordVerificationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Asks dictionaries in order until one has the word. When none has it, the result is "not found"
 * only if every dictionary answered so; if one of them could not be asked, the result says why
 * (see {@link #combine}). An interrupted lookup stops the chain.
 */
public class CompositeDictionaryService implements DictionaryService {
    private final List<DictionaryService> services;

    public CompositeDictionaryService(List<DictionaryService> services) {
        this.services = List.copyOf(services);
    }

    @Override
    public DictionaryLookupResult lookup(String english) {
        return ask(service -> service.lookup(english));
    }

    /** Asks each dictionary to refresh, so a cached one looks the word up again. */
    @Override
    public DictionaryLookupResult refresh(String english) {
        return ask(service -> service.refresh(english));
    }

    @Override
    public WordVerificationResult verify(String english) {
        List<WordVerificationResult> misses = new ArrayList<>();
        for (DictionaryService service : services) {
            WordVerificationResult result = service.verify(english);
            if (result.found()) {
                return result;
            }
            if (result.outcome() == LookupOutcome.INTERRUPTED || Thread.currentThread().isInterrupted()) {
                return WordVerificationResult.of(DictionaryLookupResult.interrupted());
            }
            misses.add(result);
        }
        String message = joinMessages(misses.stream().map(WordVerificationResult::message).toList());
        return misses.stream()
            .filter(miss -> !miss.outcome().isAnswer())
            .findFirst()
            .map(failure -> WordVerificationResult.unchecked(failure.outcome(), message))
            .orElseGet(() -> WordVerificationResult.missing(message));
    }

    @Override
    public boolean isConfigured() {
        return services.stream().anyMatch(DictionaryService::isConfigured);
    }

    /**
     * What several lookups that found nothing amount to: not found when every dictionary answered
     * so, otherwise the first reason a dictionary could not be asked, since the word may be in that
     * one. The message keeps what each dictionary said.
     */
    public static DictionaryLookupResult combine(List<DictionaryLookupResult> misses) {
        String message = joinMessages(misses.stream().map(DictionaryLookupResult::message).toList());
        return misses.stream()
            .filter(DictionaryLookupResult::unavailable)
            .findFirst()
            .map(failure -> DictionaryLookupResult.unavailable(failure.outcome(), message))
            .orElseGet(() -> DictionaryLookupResult.notFound(message));
    }

    private DictionaryLookupResult ask(Function<DictionaryService, DictionaryLookupResult> question) {
        List<DictionaryLookupResult> misses = new ArrayList<>();
        for (DictionaryService service : services) {
            DictionaryLookupResult result = question.apply(service);
            if (result.success()) {
                return result;
            }
            if (result.outcome() == LookupOutcome.INTERRUPTED || Thread.currentThread().isInterrupted()) {
                return DictionaryLookupResult.interrupted();
            }
            misses.add(result);
        }
        return combine(misses);
    }

    private static String joinMessages(List<String> messages) {
        return messages.stream()
            .filter(message -> message != null && !message.isBlank())
            .distinct()
            .collect(Collectors.joining(" | "));
    }
}
