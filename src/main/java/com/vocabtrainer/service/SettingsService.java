package com.vocabtrainer.service;

import com.vocabtrainer.repository.SettingsRepository;

import java.sql.SQLException;
import java.util.Optional;

public class SettingsService {
    public static final String ECDICT_PATH_KEY = "dictionary.ecdict.path";
    public static final String AI_PROVIDER_KEY = "ai.provider";
    public static final String AI_BASE_URL_KEY = "ai.baseUrl";
    public static final String AI_API_KEY_KEY = "ai.apiKey";
    public static final String AI_MODEL_KEY = "ai.model";
    /** The sampling temperature sent to the AI provider; when not set, none is sent. */
    public static final String AI_TEMPERATURE_KEY = "ai.temperature";
    /** The highest temperature OpenAI-compatible providers accept. */
    public static final double MAX_AI_TEMPERATURE = 2.0;
    public static final String LAST_DECK_ID_KEY = "ui.lastDeckId";
    public static final String STARTER_IMPORTED_KEY = "starter.imported";

    private final SettingsRepository settingsRepository;

    public SettingsService(SettingsRepository settingsRepository) {
        this.settingsRepository = settingsRepository;
    }

    public Optional<String> getEcdictPath() {
        return get(ECDICT_PATH_KEY).filter(value -> !value.isBlank());
    }

    public void saveEcdictPath(String path) {
        String clean = path == null ? "" : path.trim();
        if (clean.isBlank()) {
            clearEcdictPath();
            return;
        }
        save(ECDICT_PATH_KEY, clean);
    }

    public void clearEcdictPath() {
        delete(ECDICT_PATH_KEY);
    }

    public Optional<String> getAiProvider() {
        return get(AI_PROVIDER_KEY).filter(value -> !value.isBlank());
    }

    public Optional<String> getAiBaseUrl() {
        return get(AI_BASE_URL_KEY).filter(value -> !value.isBlank());
    }

    /**
     * The saved AI API key, for sending it to the provider only. It is stored as plain text in the
     * {@code settings} table (see docs/ARCHITECTURE.md); show {@link #getAiApiKeyHint()} instead.
     */
    public Optional<String> getAiApiKey() {
        return get(AI_API_KEY_KEY).filter(value -> !value.isBlank());
    }

    /** How the saved key is shown, e.g. "••••abcd"; empty when no key is saved. */
    public Optional<String> getAiApiKeyHint() {
        return getAiApiKey().map(ApiKeys::hint);
    }

    /** Deletes the saved AI API key and keeps the other AI settings. */
    public void removeAiApiKey() {
        delete(AI_API_KEY_KEY);
    }

    public Optional<String> getAiModel() {
        return get(AI_MODEL_KEY).filter(value -> !value.isBlank());
    }

    /** The saved temperature; empty when none is saved, so the provider uses its default. */
    public Optional<Double> getAiTemperature() {
        return get(AI_TEMPERATURE_KEY).flatMap(SettingsService::parseTemperature);
    }

    /** Saves the AI settings without a temperature, so the provider uses its default. */
    public void saveAiSettings(String provider, String baseUrl, String apiKey, String model) {
        saveAiSettings(provider, baseUrl, apiKey, model, "");
    }

    /**
     * Saves the AI settings after checking them.
     *
     * @param apiKey      a new key, or blank to keep the saved one
     * @param temperature a number from 0 to 2, or blank to send none
     * @throws IllegalArgumentException with a message for the user if a value is missing or invalid;
     *                                  the message never contains the key
     */
    public void saveAiSettings(String provider, String baseUrl, String apiKey, String model, String temperature) {
        String cleanProvider = provider == null || provider.isBlank() ? "openai-compatible" : provider.trim();
        String cleanBaseUrl = baseUrl == null ? "" : baseUrl.trim();
        String cleanApiKey = apiKey == null || apiKey.isBlank() ? getAiApiKey().orElse("") : apiKey.trim();
        String cleanModel = model == null ? "" : model.trim();
        String cleanTemperature = temperature == null ? "" : temperature.trim();
        if (cleanBaseUrl.isBlank() || cleanApiKey.isBlank() || cleanModel.isBlank()) {
            throw new IllegalArgumentException("AI base URL, API key, and model are required.");
        }
        ApiKeys.requireSafeToSendKey(AiEndpoint.chatCompletions(cleanBaseUrl), "The AI base URL");
        ApiKeys.requireSendable(cleanApiKey, "The API key");
        if (!cleanTemperature.isEmpty() && parseTemperature(cleanTemperature).isEmpty()) {
            throw new IllegalArgumentException("Temperature must be a number from 0 to 2, or empty for the provider's default.");
        }
        save(AI_PROVIDER_KEY, cleanProvider);
        save(AI_BASE_URL_KEY, cleanBaseUrl);
        save(AI_API_KEY_KEY, cleanApiKey);
        save(AI_MODEL_KEY, cleanModel);
        if (cleanTemperature.isEmpty()) {
            delete(AI_TEMPERATURE_KEY);
        } else {
            save(AI_TEMPERATURE_KEY, cleanTemperature);
        }
    }

    public void clearAiSettings() {
        delete(AI_PROVIDER_KEY);
        delete(AI_BASE_URL_KEY);
        delete(AI_API_KEY_KEY);
        delete(AI_MODEL_KEY);
        delete(AI_TEMPERATURE_KEY);
    }

    /** A temperature from 0 to {@link #MAX_AI_TEMPERATURE}; empty for anything else. */
    static Optional<Double> parseTemperature(String value) {
        try {
            double parsed = Double.parseDouble(value == null ? "" : value.trim());
            return parsed >= 0 && parsed <= MAX_AI_TEMPERATURE ? Optional.of(parsed) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** The deck the user last worked in; empty if never saved or not a valid id. */
    public Optional<Long> getLastDeckId() {
        Optional<String> value = get(LAST_DECK_ID_KEY);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(value.get().trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public void saveLastDeckId(long deckId) {
        save(LAST_DECK_ID_KEY, String.valueOf(deckId));
    }

    /** Whether this database has already decided about the bundled starter words (imported or skipped). */
    public boolean isStarterImported() {
        return get(STARTER_IMPORTED_KEY).map(value -> value.trim().equalsIgnoreCase("true")).orElse(false);
    }

    public void markStarterImported() {
        save(STARTER_IMPORTED_KEY, "true");
    }

    public Optional<String> get(String key) {
        try {
            return settingsRepository.find(key);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read setting: " + key, e);
        }
    }

    public void save(String key, String value) {
        try {
            settingsRepository.save(key, value == null ? "" : value);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot save setting: " + key, e);
        }
    }

    public void delete(String key) {
        try {
            settingsRepository.delete(key);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot delete setting: " + key, e);
        }
    }
}
