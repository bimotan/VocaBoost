package com.vocabtrainer.service;

import com.vocabtrainer.repository.AiCacheRepository;

import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

public final class AiServiceFactory {
    private static final Logger LOGGER = Logger.getLogger(AiServiceFactory.class.getName());

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
     * returns the mock text for that one call and is retried next time. While the saved offline
     * mode is on ({@link SettingsService#isOfflineMode()}), the mock text is used without a request.
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
        BooleanSupplier offline = settingsService == null ? () -> false : settingsService::isOfflineMode;
        return new OfflineAwareAiService(new FallbackAiService(primary, mock), mock, offline);
    }

    /**
     * The configured provider on its own, without the cache, the mock fallback or offline mode, so
     * every call sends a fresh request and a failure is thrown instead of hidden. Empty when no
     * provider is configured. Used to test the AI settings, which checks offline mode itself.
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
        String temperature = configuredValue(settingsService, SettingsService.AI_TEMPERATURE_KEY, config,
            "VOCABOOST_AI_TEMPERATURE");
        if ("mock".equalsIgnoreCase(provider) || "off".equalsIgnoreCase(provider) || "disabled".equalsIgnoreCase(provider)) {
            return null;
        }
        if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
            return null;
        }
        Optional<Double> parsedTemperature = SettingsService.parseTemperature(temperature);
        if (!temperature.isBlank() && parsedTemperature.isEmpty()) {
            LOGGER.warning("Ignoring the AI temperature '" + temperature
                + "': it must be a number from 0 to 2; the provider's default is used.");
        }
        return new OpenAiCompatibleAiService(baseUrl, apiKey, model, parsedTemperature.orElse(null));
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
