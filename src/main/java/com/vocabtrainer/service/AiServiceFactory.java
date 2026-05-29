package com.vocabtrainer.service;

import com.vocabtrainer.repository.AiCacheRepository;

import java.util.Map;

public final class AiServiceFactory {
    private AiServiceFactory() {
    }

    public static AiService create(AiCacheRepository cacheRepository) {
        return create(cacheRepository, System.getenv());
    }

    public static AiService create(AiCacheRepository cacheRepository, Map<String, String> config) {
        String provider = value(config, "VOCABOOST_AI_PROVIDER");
        String baseUrl = value(config, "VOCABOOST_AI_BASE_URL");
        String apiKey = value(config, "VOCABOOST_AI_API_KEY");
        String model = value(config, "VOCABOOST_AI_MODEL");
        AiService mock = new MockAiService();
        if (provider.isBlank() || baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
            return mock;
        }
        AiService primary = new OpenAiCompatibleAiService(baseUrl, apiKey, model);
        AiService fallback = new FallbackAiService(primary, mock);
        return cacheRepository == null ? fallback : new CachingAiService(fallback, cacheRepository);
    }

    private static String value(Map<String, String> config, String key) {
        if (config == null) {
            return "";
        }
        String value = config.get(key);
        return value == null ? "" : value.trim();
    }
}
