package com.vocabtrainer.ui;

import com.vocabtrainer.service.AiService;
import com.vocabtrainer.service.DictionaryService;

import java.util.function.Supplier;

/**
 * The dictionary and AI services built from the saved settings. The AI service is rebuilt when the
 * user saves or clears its settings; background work takes the service when it starts. The
 * dictionary service is built once: it reads the imported ECDICT dictionary at each lookup, so a new
 * import needs no rebuild.
 */
public final class ConfiguredServices {
    private final DictionaryService dictionary;
    private final Supplier<AiService> aiFactory;
    private volatile AiService ai;

    public ConfiguredServices(Supplier<DictionaryService> dictionaryFactory, Supplier<AiService> aiFactory) {
        this.dictionary = dictionaryFactory.get();
        this.aiFactory = aiFactory;
        this.ai = aiFactory.get();
    }

    public DictionaryService dictionary() {
        return dictionary;
    }

    public AiService ai() {
        return ai;
    }

    public void reloadAi() {
        ai = aiFactory.get();
    }
}
