package com.vocabtrainer.service;

import com.vocabtrainer.repository.DictionaryCacheRepository;

import java.util.ArrayList;
import java.util.List;

public final class DictionaryServiceFactory {
    private DictionaryServiceFactory() {
    }

    public static DictionaryService create(DictionaryCacheRepository cacheRepository) {
        String baseUrl = System.getenv("DICTIONARY_API_BASE_URL");
        String apiKey = System.getenv("DICTIONARY_API_KEY");
        List<DictionaryService> services = new ArrayList<>();
        services.add(new LocalDictionaryService());
        if (baseUrl != null && !baseUrl.isBlank()) {
            services.add(new HttpDictionaryService(baseUrl, apiKey));
        }
        services.add(new PublicOnlineDictionaryService());
        services.add(new MockDictionaryService());
        return new CachingDictionaryService(new CompositeDictionaryService(services), cacheRepository);
    }
}
