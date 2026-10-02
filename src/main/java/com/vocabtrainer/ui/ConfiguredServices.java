package com.vocabtrainer.ui;

import com.vocabtrainer.service.AiService;
import com.vocabtrainer.service.DictionaryService;

import java.util.function.Supplier;

/**
 * The dictionary and AI services built from the saved settings. They are rebuilt when the user saves
 * or clears those settings; background work takes the service when it starts.
 */
public final class ConfiguredServices {
    private final Supplier<DictionaryService> dictionaryFactory;
    private final Supplier<AiService> aiFactory;
    private volatile DictionaryService dictionary;
    private volatile AiService ai;

    public ConfiguredServices(Supplier<DictionaryService> dictionaryFactory, Supplier<AiService> aiFactory) {
        this.dictionaryFactory = dictionaryFactory;
        this.aiFactory = aiFactory;
        this.dictionary = dictionaryFactory.get();
        this.ai = aiFactory.get();
    }

    public DictionaryService dictionary() {
        return dictionary;
    }

    public AiService ai() {
        return ai;
    }

    public void reloadDictionary() {
        dictionary = dictionaryFactory.get();
    }

    public void reloadAi() {
        ai = aiFactory.get();
    }
}
