package com.vocabtrainer.service;

import com.vocabtrainer.repository.AiCacheRepository;

import java.util.Map;

public final class AiServiceFactory {
    private AiServiceFactory() {
    }

    public static AiService create(AiCacheRepository cacheRepository) {
        return create(cacheRepository, null, System.getenv());
    }

    public static AiService create(AiCacheRepository cacheRepository, SettingsService settingsService) {
        return create(cacheRepository, settingsService, System.getenv());
    }

    public static AiService create(AiCacheRepository cacheRepository, Map<String, String> config) {
        return create(cacheRepository, null, config);
    }

    public static AiService create(AiCacheRepository cacheRepository, SettingsService settingsService,
                                   Map<String, String> config) {
        String provider = configuredValue(settingsService, SettingsService.AI_PROVIDER_KEY, config, "VOCABOOST_AI_PROVIDER");
        String baseUrl = configuredValue(settingsService, SettingsService.AI_BASE_URL_KEY, config, "VOCABOOST_AI_BASE_URL");
        String apiKey = configuredValue(settingsService, SettingsService.AI_API_KEY_KEY, config, "VOCABOOST_AI_API_KEY");
        String model = configuredValue(settingsService, SettingsService.AI_MODEL_KEY, config, "VOCABOOST_AI_MODEL");
        AiService mock = new MockAiService();
        if ("mock".equalsIgnoreCase(provider) || "off".equalsIgnoreCase(provider) || "disabled".equalsIgnoreCase(provider)) {
            return mock;
        }
        if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
            return mock;
        }
        if (provider.isBlank()) {
            provider = "openai-compatible";
        }
        AiService primary = new OpenAiCompatibleAiService(baseUrl, apiKey, model);
        AiService fallback = new FallbackAiService(primary, mock);
        return cacheRepository == null ? fallback : new CachingAiService(fallback, cacheRepository);
    }

    private static String configuredValue(SettingsService settingsService, String settingsKey,
                                          Map<String, String> config, String envKey) {
        if (settingsService != null) {
            String value = settingsService.get(settingsKey).orElse("").trim();
            if (!value.isBlank()) {
                return value;
            }
        }
        return value(config, envKey);
    }

    private static String value(Map<String, String> config, String key) {
        if (config == null) {
            return "";
        }
        String value = config.get(key);
        return value == null ? "" : value.trim();
    }
}
