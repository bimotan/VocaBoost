package com.vocabtrainer.ui.importing;

import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.AiCacheRepository;
import com.vocabtrainer.service.AiService;
import com.vocabtrainer.service.AiServiceFactory;
import com.vocabtrainer.service.SettingsService;
import com.vocabtrainer.ui.ConfiguredServices;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.UiErrors;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.sql.SQLException;
import java.util.Optional;

/**
 * Saves, clears and tests the optional AI provider used for review explanations, and clears its
 * cache. A saved API key is never put back into the form: the box shows its last characters, and
 * Replace or Remove change it.
 */
final class AiSettingsBox {
    private static final String KEY_STORAGE_NOTE = "The API key is stored unencrypted in vocab.db in the data folder,"
        + " which only your user account can open where the system allows it. It is only sent to the base URL"
        + " (https, or http to this computer) and is never shown again, logged, or written to exports and JSON backups."
        + " To keep it out of the database, set VOCABOOST_AI_API_KEY instead.";

    private final ViewContext context;
    private final SettingsService settingsService;
    private final AiCacheRepository aiCacheRepository;
    private final ConfiguredServices configured;
    private final Label statusLabel = new Label();
    private final PasswordField apiKeyField = new PasswordField();
    private final Label apiKeyStatus = new Label();
    private final Button replaceKeyButton = new Button("Replace");
    private final Button removeKeyButton = new Button("Remove");
    private final VBox root;
    /** Whether the user chose Replace, so the key field is shown although a key is saved. */
    private boolean replacingKey;

    AiSettingsBox(ViewContext context, SettingsService settingsService, AiCacheRepository aiCacheRepository,
                  ConfiguredServices configured) {
        this.context = context;
        this.settingsService = settingsService;
        this.aiCacheRepository = aiCacheRepository;
        this.configured = configured;

        TextField providerField = new TextField(settingsService.getAiProvider().orElse("openai-compatible"));
        providerField.setId("aiProviderField");
        providerField.setPromptText("openai-compatible");
        TextField baseUrlField = new TextField(settingsService.getAiBaseUrl().orElse(""));
        baseUrlField.setId("aiBaseUrlField");
        baseUrlField.setPromptText("https://api.example.com/v1 (/chat/completions is added)");
        apiKeyField.setId("aiApiKeyField");
        apiKeyStatus.setId("aiKeyStatusLabel");
        replaceKeyButton.setId("replaceAiKeyButton");
        replaceKeyButton.setOnAction(event -> {
            replacingKey = true;
            showKeyState();
            apiKeyField.requestFocus();
        });
        removeKeyButton.setId("removeAiKeyButton");
        removeKeyButton.setOnAction(event -> removeKey());
        TextField modelField = new TextField(settingsService.getAiModel().orElse(""));
        modelField.setId("aiModelField");
        modelField.setPromptText("model name");
        TextField temperatureField = new TextField(settingsService.getAiTemperature().map(String::valueOf).orElse(""));
        temperatureField.setId("aiTemperatureField");
        temperatureField.setPromptText("provider default (0 to 2)");
        statusLabel.setText(aiStatusText());
        statusLabel.setId("aiStatusLabel");
        statusLabel.setWrapText(true);

        Button saveButton = new Button("Save AI Settings");
        saveButton.setId("saveAiButton");
        saveButton.setOnAction(event -> {
            try {
                settingsService.saveAiSettings(
                    providerField.getText(),
                    baseUrlField.getText(),
                    apiKeyField.getText(),
                    modelField.getText(),
                    temperatureField.getText()
                );
                replacingKey = false;
                apiKeyField.clear();
                showKeyState();
                reloadAiService();
                statusLabel.setText("Saved. " + aiStatusText());
            } catch (RuntimeException e) {
                context.errors().logFailure("Save AI settings failed", e);
                statusLabel.setText(UiErrors.rootMessage(e));
            }
        });

        Button clearButton = new Button("Clear AI Settings");
        clearButton.setId("clearAiButton");
        clearButton.setOnAction(event -> context.errors().guard("Clear AI settings failed", () -> {
            settingsService.clearAiSettings();
            providerField.setText("openai-compatible");
            baseUrlField.clear();
            apiKeyField.clear();
            replacingKey = false;
            showKeyState();
            modelField.clear();
            temperatureField.clear();
            reloadAiService();
            statusLabel.setText("Cleared. " + aiStatusText());
        }));

        Button testButton = new Button("Test AI Explanation");
        testButton.setId("testAiButton");
        testButton.setOnAction(event -> testProvider(testButton));

        Button clearCacheButton = new Button("Clear AI cache");
        clearCacheButton.setId("clearAiCacheButton");
        clearCacheButton.setOnAction(event -> clearCache());

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.add(new Label("Provider"), 0, 0);
        form.add(providerField, 1, 0);
        form.add(new Label("Base URL"), 0, 1);
        form.add(baseUrlField, 1, 1);
        HBox keyRow = new HBox(10, apiKeyField, apiKeyStatus, replaceKeyButton, removeKeyButton);
        keyRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(apiKeyField, Priority.ALWAYS);
        form.add(new Label("API key"), 0, 2);
        form.add(keyRow, 1, 2);
        form.add(new Label("Model"), 0, 3);
        form.add(modelField, 1, 3);
        form.add(new Label("Temperature"), 0, 4);
        form.add(temperatureField, 1, 4);
        GridPane.setHgrow(providerField, Priority.ALWAYS);
        GridPane.setHgrow(baseUrlField, Priority.ALWAYS);
        GridPane.setHgrow(keyRow, Priority.ALWAYS);
        GridPane.setHgrow(modelField, Priority.ALWAYS);
        HBox buttons = new HBox(10, saveButton, clearButton, testButton, clearCacheButton);
        Label keyNote = new Label(KEY_STORAGE_NOTE);
        keyNote.setId("aiKeyNoteLabel");
        keyNote.setWrapText(true);
        keyNote.setStyle("-fx-text-fill: #6b7280;");
        showKeyState();
        root = new VBox(10, Widgets.sectionTitle("AI Explanation Provider"), form, keyNote, buttons, statusLabel);
    }

    Node root() {
        return root;
    }

    private void testProvider(Button testButton) {
        // Ask the saved provider directly: no cache, so a fixed key or model shows up at once,
        // and no mock fallback, so a failure shows the provider's error instead of mock text.
        Optional<AiService> provider;
        try {
            provider = AiServiceFactory.createUncachedProvider(settingsService);
        } catch (RuntimeException e) {
            // Reading the saved settings can fail; report it here instead of on the FX thread.
            context.errors().logFailure("AI test failed", e);
            statusLabel.setText("AI test failed: " + UiErrors.rootMessage(e));
            return;
        }
        if (provider.isEmpty()) {
            statusLabel.setText("AI test skipped: no AI provider is configured. Save a base URL, API key and"
                + " model first; until then the offline mock explanation is used.");
            return;
        }
        AiService uncachedProvider = provider.get();
        WordCard sample = WordCard.createNew(context.decks().currentId(), "lucid", "清晰的; 易懂的");
        context.async().run(
            () -> uncachedProvider.explain(sample),
            text -> statusLabel.setText("AI test succeeded. Provider response:" + System.lineSeparator() + text),
            error -> statusLabel.setText("AI test failed: " + UiErrors.rootMessage(error)),
            statusLabel,
            "Testing AI explanation...",
            testButton
        );
    }

    /**
     * Shows the key field when no key is saved or the user replaces it; otherwise the saved key's
     * last characters with Replace and Remove.
     */
    private void showKeyState() {
        Optional<String> hint = settingsService.getAiApiKeyHint();
        boolean saved = hint.isPresent();
        boolean typing = !saved || replacingKey;
        setShown(apiKeyField, typing);
        setShown(apiKeyStatus, saved);
        setShown(replaceKeyButton, saved && !replacingKey);
        setShown(removeKeyButton, saved);
        apiKeyField.setPromptText(saved ? "New API key (leave empty to keep the saved one)" : "API key");
        apiKeyStatus.setText(hint.map(value -> value.length() > 4 ? "Saved, ends with " + value : "Saved").orElse(""));
    }

    private static void setShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    private void removeKey() {
        if (!context.dialogs().confirm("Remove API key", "Remove the saved API key?",
            "Review explanations use the offline mock text until a key is saved again.")) {
            return;
        }
        context.errors().guard("Remove API key failed", () -> {
            settingsService.removeAiApiKey();
            replacingKey = false;
            apiKeyField.clear();
            showKeyState();
            reloadAiService();
            statusLabel.setText("API key removed. " + aiStatusText());
        });
    }

    /** Deletes every cached explanation, so each word is explained afresh the next time. */
    private void clearCache() {
        if (!context.dialogs().confirm("Clear AI cache", "Delete all cached AI explanations?",
            "Every word is explained afresh by the AI provider the next time it is answered.")) {
            return;
        }
        try {
            int deleted = aiCacheRepository.deleteAll();
            statusLabel.setText("Cleared " + deleted + " cached AI explanation" + (deleted == 1 ? "." : "s."));
        } catch (SQLException e) {
            context.errors().reportFailure("Clear AI cache failed", e);
        }
    }

    private void reloadAiService() {
        configured.reloadAi();
        context.changes().publish(DataChange.SETTINGS);
    }

    private String aiStatusText() {
        if (configured.ai().isAvailable()) {
            return "AI provider configured. Review explanations use HTTP provider with local cache.";
        }
        return "AI provider not configured. Mock explanation is used offline.";
    }
}
