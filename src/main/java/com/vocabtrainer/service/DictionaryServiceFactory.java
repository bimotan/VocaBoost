package com.vocabtrainer.service;

import com.vocabtrainer.repository.DictionaryCacheRepository;

import java.util.ArrayList;
import java.util.List;

public final class DictionaryServiceFactory {
    private DictionaryServiceFactory() {
    }

    /**
     * The app's dictionary chain: the offline dictionaries ({@code local}: imported ECDICT, then the
     * bundled starter words), then, through the lookup cache, the configured API, the public online
     * dictionaries and the mock fallback. The offline dictionaries are asked before the cache and
     * are never cached: they answer at once, and a cached copy would hide a re-imported ECDICT.
     */
    public static DictionaryService create(DictionaryCacheRepository cacheRepository, LocalDictionaryService local) {
        String baseUrl = System.getenv("DICTIONARY_API_BASE_URL");
        String apiKey = System.getenv("DICTIONARY_API_KEY");
        List<DictionaryService> online = new ArrayList<>();
        if (baseUrl != null && !baseUrl.isBlank()) {
            online.add(new HttpDictionaryService(baseUrl, apiKey));
        }
        online.add(new PublicOnlineDictionaryService());
        online.add(new MockDictionaryService());
        return new CompositeDictionaryService(List.of(
            local,
            new CachingDictionaryService(new CompositeDictionaryService(online), cacheRepository)
        ));
    }
}
