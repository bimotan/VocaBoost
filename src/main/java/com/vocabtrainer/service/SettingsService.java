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

    public Optional<String> getAiApiKey() {
        return get(AI_API_KEY_KEY).filter(value -> !value.isBlank());
    }

    public Optional<String> getAiModel() {
        return get(AI_MODEL_KEY).filter(value -> !value.isBlank());
    }

    public void saveAiSettings(String provider, String baseUrl, String apiKey, String model) {
        String cleanProvider = provider == null || provider.isBlank() ? "openai-compatible" : provider.trim();
        String cleanBaseUrl = baseUrl == null ? "" : baseUrl.trim();
        String cleanApiKey = apiKey == null ? "" : apiKey.trim();
        String cleanModel = model == null ? "" : model.trim();
        if (cleanBaseUrl.isBlank() || cleanApiKey.isBlank() || cleanModel.isBlank()) {
            throw new IllegalArgumentException("AI base URL, API key, and model are required.");
        }
        save(AI_PROVIDER_KEY, cleanProvider);
        save(AI_BASE_URL_KEY, cleanBaseUrl);
        save(AI_API_KEY_KEY, cleanApiKey);
        save(AI_MODEL_KEY, cleanModel);
    }

    public void clearAiSettings() {
        delete(AI_PROVIDER_KEY);
        delete(AI_BASE_URL_KEY);
        delete(AI_API_KEY_KEY);
        delete(AI_MODEL_KEY);
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
