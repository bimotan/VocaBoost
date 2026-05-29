package com.vocabtrainer.service;

import com.vocabtrainer.repository.DictionaryCacheRepository;

import java.util.ArrayList;
import java.util.List;

public final class DictionaryServiceFactory {
    private DictionaryServiceFactory() {
    }

    public static DictionaryService create(DictionaryCacheRepository cacheRepository) {
        return create(cacheRepository, null);
    }

    public static DictionaryService create(DictionaryCacheRepository cacheRepository, SettingsService settingsService) {
        String baseUrl = System.getenv("DICTIONARY_API_BASE_URL");
        String apiKey = System.getenv("DICTIONARY_API_KEY");
        String ecdictPath = settingsService == null
            ? System.getenv("ECDICT_CSV_PATH")
            : settingsService.getEcdictPath().orElse(System.getenv("ECDICT_CSV_PATH"));
        List<DictionaryService> services = new ArrayList<>();
        services.add(new LocalDictionaryService(ecdictPath));
        if (baseUrl != null && !baseUrl.isBlank()) {
            services.add(new HttpDictionaryService(baseUrl, apiKey));
        }
        services.add(new PublicOnlineDictionaryService());
        services.add(new MockDictionaryService());
        return new CachingDictionaryService(new CompositeDictionaryService(services), cacheRepository);
    }
}
