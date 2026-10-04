package com.vocabtrainer.service;

import com.vocabtrainer.repository.SettingsRepository;
import com.vocabtrainer.service.scheduling.SchedulingOptions;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.vocabtrainer.util.Messages.tr;

public class SettingsService {
    private static final Logger LOGGER = Logger.getLogger(SettingsService.class.getName());

    public static final String ECDICT_PATH_KEY = "dictionary.ecdict.path";
    public static final String AI_PROVIDER_KEY = "ai.provider";
    public static final String AI_BASE_URL_KEY = "ai.baseUrl";
    public static final String AI_API_KEY_KEY = "ai.apiKey";
    public static final String AI_MODEL_KEY = "ai.model";
    /** The sampling temperature sent to the AI provider; when not set, none is sent. */
    public static final String AI_TEMPERATURE_KEY = "ai.temperature";
    /** The highest temperature OpenAI-compatible providers accept. */
    public static final double MAX_AI_TEMPERATURE = 2.0;
    /** When review answers are explained without Explain being pressed, see {@link AutoExplain}. */
    public static final String AI_AUTO_EXPLAIN_KEY = "ai.autoExplain";
    public static final String LAST_DECK_ID_KEY = "ui.lastDeckId";
    /** "true" while offline mode is on: no online dictionary lookups and no AI requests. */
    public static final String OFFLINE_MODE_KEY = "network.offline";
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
            throw new IllegalArgumentException(tr("ai.error.required"));
        }
        ApiKeys.requireSafeToSendKey(AiEndpoint.chatCompletions(cleanBaseUrl), tr("ai.name.baseUrl"));
        ApiKeys.requireSendable(cleanApiKey, tr("ai.name.apiKey"));
        Optional<Double> parsedTemperature = parseTemperature(cleanTemperature);
        if (!cleanTemperature.isEmpty() && parsedTemperature.isEmpty()) {
            throw new IllegalArgumentException(tr("ai.error.temperature"));
        }
        save(AI_PROVIDER_KEY, cleanProvider);
        save(AI_BASE_URL_KEY, cleanBaseUrl);
        save(AI_API_KEY_KEY, cleanApiKey);
        save(AI_MODEL_KEY, cleanModel);
        if (parsedTemperature.isEmpty()) {
            delete(AI_TEMPERATURE_KEY);
        } else {
            save(AI_TEMPERATURE_KEY, String.valueOf(parsedTemperature.get()));
        }
    }

    /**
     * When the Review tab asks the AI provider to explain an answer by itself; {@link AutoExplain#DEFAULT}
     * when nothing is saved. A saved value that is not a choice is logged and read as the default.
     */
    public AutoExplain getAutoExplain() {
        Optional<String> saved = get(AI_AUTO_EXPLAIN_KEY).filter(value -> !value.isBlank());
        if (saved.isEmpty()) {
            return AutoExplain.DEFAULT;
        }
        Optional<AutoExplain> choice = AutoExplain.fromSetting(saved.get());
        if (choice.isEmpty()) {
            LOGGER.warning("Ignoring setting " + AI_AUTO_EXPLAIN_KEY + "=" + saved.get()
                + ": it must be always, mistakes or never");
        }
        return choice.orElse(AutoExplain.DEFAULT);
    }

    public void saveAutoExplain(AutoExplain choice) {
        save(AI_AUTO_EXPLAIN_KEY, (choice == null ? AutoExplain.DEFAULT : choice).settingValue());
    }

    /** Deletes the provider settings; when to explain answers is the user's preference and stays. */
    public void clearAiSettings() {
        delete(AI_PROVIDER_KEY);
        delete(AI_BASE_URL_KEY);
        delete(AI_API_KEY_KEY);
        delete(AI_MODEL_KEY);
        delete(AI_TEMPERATURE_KEY);
    }

    /**
     * A temperature from 0 to {@link #MAX_AI_TEMPERATURE} written as a plain decimal number (such as
     * "0.7" or "1"); empty for anything else, including Java's "NaN", "1d" or hexadecimal forms.
     */
    static Optional<Double> parseTemperature(String value) {
        try {
            BigDecimal parsed = new BigDecimal(value == null ? "" : value.trim());
            if (parsed.signum() < 0 || parsed.compareTo(BigDecimal.valueOf(MAX_AI_TEMPERATURE)) > 0) {
                return Optional.empty();
            }
            // BigDecimal has no negative zero, so "-0" is sent as 0.0.
            return Optional.of(parsed.doubleValue());
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Whether offline mode is on. It is read from the database at every online lookup and AI
     * request, so switching it applies at once; if it cannot be read, the app stays offline.
     */
    public boolean isOfflineMode() {
        try {
            return get(OFFLINE_MODE_KEY).map(value -> value.trim().equalsIgnoreCase("true")).orElse(false);
        } catch (IllegalStateException e) {
            LOGGER.log(Level.WARNING, "Cannot read whether offline mode is on; staying offline", e);
            return true;
        }
    }

    public void saveOfflineMode(boolean offline) {
        save(OFFLINE_MODE_KEY, String.valueOf(offline));
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

    // ---- Review scheduling ----

    /** The chance of recall review intervals aim for, from 0.7 to 0.97; 0.9 when not set. */
    public static final String DESIRED_RETENTION_KEY = "scheduler.desiredRetention";
    /** The hour (0 to 23) at which a new study day starts; 4 when not set. */
    public static final String DAY_ROLLOVER_HOUR_KEY = "scheduler.dayRolloverHour";

    /**
     * How reviews are scheduled: the defaults, with the saved desired retention and day rollover
     * hour. A saved value that is not a valid number is logged and ignored.
     */
    public SchedulingOptions getSchedulingOptions() {
        SchedulingOptions options = SchedulingOptions.defaults();
        Optional<String> retention = get(DESIRED_RETENTION_KEY).filter(value -> !value.isBlank());
        if (retention.isPresent()) {
            try {
                options = options.withDesiredRetention(Double.parseDouble(retention.get().trim()));
            } catch (IllegalArgumentException e) {
                Logger.getLogger(SettingsService.class.getName()).warning(
                    "Ignoring setting " + DESIRED_RETENTION_KEY + "=" + retention.get() + ": " + e.getMessage());
            }
        }
        Optional<String> rollover = get(DAY_ROLLOVER_HOUR_KEY).filter(value -> !value.isBlank());
        if (rollover.isPresent()) {
            try {
                options = options.withDayRolloverHour(Integer.parseInt(rollover.get().trim()));
            } catch (IllegalArgumentException e) {
                Logger.getLogger(SettingsService.class.getName()).warning(
                    "Ignoring setting " + DAY_ROLLOVER_HOUR_KEY + "=" + rollover.get() + ": " + e.getMessage());
            }
        }
        return options;
    }

    public Optional<String> get(String key) {
        try {
            return settingsRepository.find(key);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read setting: " + key, e);
        }
    }

    /** Every setting whose key starts with {@code prefix}, by key. */
    public Map<String, String> getByPrefix(String prefix) {
        try {
            return settingsRepository.findByPrefix(prefix);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the settings " + prefix + "*", e);
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
