package com.vocabtrainer.service;

import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.domain.LookupOutcome;
import com.vocabtrainer.domain.WordVerificationResult;

import java.util.function.BooleanSupplier;

/**
 * An online dictionary that is not asked while offline mode is on: every lookup then ends with
 * {@link LookupOutcome#OFFLINE} without a request. Offline mode is checked at each lookup, so
 * switching it applies at once.
 */
final class OfflineAwareDictionaryService implements DictionaryService {
    private final DictionaryService online;
    private final BooleanSupplier offline;
    private final String name;

    /** @param name the dictionary in messages, e.g. "在线词典" */
    OfflineAwareDictionaryService(DictionaryService online, BooleanSupplier offline, String name) {
        this.online = online;
        this.offline = offline;
        this.name = name;
    }

    @Override
    public DictionaryLookupResult lookup(String english) {
        return offline.getAsBoolean() ? skipped() : online.lookup(english);
    }

    @Override
    public DictionaryLookupResult refresh(String english) {
        return offline.getAsBoolean() ? skipped() : online.refresh(english);
    }

    @Override
    public WordVerificationResult verify(String english) {
        return offline.getAsBoolean() ? WordVerificationResult.of(skipped()) : online.verify(english);
    }

    @Override
    public boolean isConfigured() {
        return online.isConfigured();
    }

    private DictionaryLookupResult skipped() {
        return DictionaryLookupResult.unavailable(LookupOutcome.OFFLINE, name + "：离线模式已开启，没有查询。");
    }
}
