package com.vocabtrainer.service;

import com.vocabtrainer.repository.AiCacheRepository;

import java.util.Map;
import java.util.Optional;

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

    /**
     * The service review uses: the configured provider behind the cache, with the mock as a
     * fallback outside it. Only real provider output reaches {@code ai_cache}; a failed request
     * returns the mock text for that one call and is retried next time.
     */
    public static AiService create(AiCacheRepository cacheRepository, SettingsService settingsService,
                                   Map<String, String> config) {
        AiService mock = new MockAiService();
        OpenAiCompatibleAiService provider = configuredProvider(settingsService, config);
        if (provider == null) {
            return mock;
        }
        AiService primary = cacheRepository == null
            ? provider
            : new CachingAiService(provider, cacheRepository, provider.cacheIdentity());
        return new FallbackAiService(primary, mock);
    }

    /**
     * The configured provider on its own, without the cache or the mock fallback, so every call
     * sends a fresh request and a failure is thrown instead of hidden. Empty when no provider is
     * configured. Used to test the AI settings.
     */
    public static Optional<AiService> createUncachedProvider(SettingsService settingsService) {
        return createUncachedProvider(settingsService, System.getenv());
    }

    public static Optional<AiService> createUncachedProvider(SettingsService settingsService,
                                                             Map<String, String> config) {
        return Optional.ofNullable(configuredProvider(settingsService, config));
    }

    private static OpenAiCompatibleAiService configuredProvider(SettingsService settingsService,
                                                                Map<String, String> config) {
        String provider = configuredValue(settingsService, SettingsService.AI_PROVIDER_KEY, config, "VOCABOOST_AI_PROVIDER");
        String baseUrl = configuredValue(settingsService, SettingsService.AI_BASE_URL_KEY, config, "VOCABOOST_AI_BASE_URL");
        String apiKey = configuredValue(settingsService, SettingsService.AI_API_KEY_KEY, config, "VOCABOOST_AI_API_KEY");
        String model = configuredValue(settingsService, SettingsService.AI_MODEL_KEY, config, "VOCABOOST_AI_MODEL");
        if ("mock".equalsIgnoreCase(provider) || "off".equalsIgnoreCase(provider) || "disabled".equalsIgnoreCase(provider)) {
            return null;
        }
        if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
            return null;
        }
        return new OpenAiCompatibleAiService(baseUrl, apiKey, model);
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
