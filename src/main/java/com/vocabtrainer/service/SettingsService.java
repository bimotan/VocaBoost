package com.vocabtrainer.service;

import com.vocabtrainer.repository.SettingsRepository;

import java.sql.SQLException;
import java.util.Optional;

public class SettingsService {
    public static final String ECDICT_PATH_KEY = "dictionary.ecdict.path";
    public static final String ECDICT_LAST_LOADED_COUNT_KEY = "dictionary.lastLoadedCount";
    public static final String ECDICT_LAST_LOADED_AT_KEY = "dictionary.lastLoadedAt";

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
