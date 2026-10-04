package com.vocabtrainer.ui.settings;

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
import com.vocabtrainer.util.Messages;
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
import java.util.List;
import java.util.Optional;

import static com.vocabtrainer.util.Messages.tr;

/**
 * Saves, clears and tests the optional AI provider used for review explanations, and clears its
 * cache. A saved API key is never put back into the form: the box shows its last characters, and
 * Replace or Remove change it.
 */
final class AiSettingsBox {
    private final ViewContext context;
    private final SettingsService settingsService;
    private final AiCacheRepository aiCacheRepository;
    private final ConfiguredServices configured;
    private final Label statusLabel = new Label();
    private final PasswordField apiKeyField = new PasswordField();
    private final Label apiKeyStatus = new Label();
    private final Button replaceKeyButton = new Button(tr("ai.key.replace"));
    private final Button removeKeyButton = new Button(tr("ai.key.remove"));
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
        baseUrlField.setPromptText(tr("ai.baseUrl.prompt"));
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
        modelField.setPromptText(tr("ai.model.prompt"));
        TextField temperatureField = new TextField(settingsService.getAiTemperature().map(String::valueOf).orElse(""));
        temperatureField.setId("aiTemperatureField");
        temperatureField.setPromptText(tr("ai.temperature.prompt"));
        statusLabel.setText(aiStatusText());
        statusLabel.setId("aiStatusLabel");
        context.changes().subscribe(changes -> {
            if (changes.contains(DataChange.SETTINGS)) {
                // Offline mode or the provider changed.
                statusLabel.setText(aiStatusText());
            }
        });
        statusLabel.setWrapText(true);

        Button saveButton = new Button(tr("ai.save"));
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
                statusLabel.setText(Messages.sentences(List.of(tr("review.saved"), aiStatusText())));
            } catch (RuntimeException e) {
                context.errors().logFailure(tr("ai.save.failed"), e);
                statusLabel.setText(UiErrors.rootMessage(e));
            }
        });

        Button clearButton = new Button(tr("ai.clear"));
        clearButton.setId("clearAiButton");
        clearButton.setOnAction(event -> context.errors().guard(tr("ai.clear.failed"), () -> {
            settingsService.clearAiSettings();
            providerField.setText("openai-compatible");
            baseUrlField.clear();
            apiKeyField.clear();
            replacingKey = false;
            showKeyState();
            modelField.clear();
            temperatureField.clear();
            reloadAiService();
            statusLabel.setText(Messages.sentences(List.of(tr("ai.cleared"), aiStatusText())));
        }));

        Button testButton = new Button(tr("ai.test"));
        testButton.setId("testAiButton");
        testButton.setOnAction(event -> testProvider(testButton));

        Button clearCacheButton = new Button(tr("ai.cache.clear"));
        clearCacheButton.setId("clearAiCacheButton");
        clearCacheButton.setOnAction(event -> clearCache());

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.add(Widgets.formLabel(tr("ai.provider"), providerField), 0, 0);
        form.add(providerField, 1, 0);
        form.add(Widgets.formLabel(tr("ai.baseUrl"), baseUrlField), 0, 1);
        form.add(baseUrlField, 1, 1);
        HBox keyRow = new HBox(10, apiKeyField, apiKeyStatus, replaceKeyButton, removeKeyButton);
        keyRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(apiKeyField, Priority.ALWAYS);
        form.add(Widgets.formLabel(tr("ai.key"), apiKeyField), 0, 2);
        form.add(keyRow, 1, 2);
        form.add(Widgets.formLabel(tr("ai.model"), modelField), 0, 3);
        form.add(modelField, 1, 3);
        form.add(Widgets.formLabel(tr("ai.temperature"), temperatureField), 0, 4);
        form.add(temperatureField, 1, 4);
        GridPane.setHgrow(providerField, Priority.ALWAYS);
        GridPane.setHgrow(baseUrlField, Priority.ALWAYS);
        GridPane.setHgrow(keyRow, Priority.ALWAYS);
        GridPane.setHgrow(modelField, Priority.ALWAYS);
        HBox buttons = new HBox(10, saveButton, clearButton, testButton, clearCacheButton);
        Label privacyNote = new Label(tr("ai.privacy.requests") + System.lineSeparator() + tr("ai.privacy.key"));
        privacyNote.setId("aiPrivacyNoteLabel");
        privacyNote.setWrapText(true);
        privacyNote.getStyleClass().add("muted-text");
        showKeyState();
        root = new VBox(10, Widgets.sectionTitle(tr("ai.title")), form, privacyNote, buttons, statusLabel);
    }

    Node root() {
        return root;
    }

    private void testProvider(Button testButton) {
        if (settingsService.isOfflineMode()) {
            statusLabel.setText(tr("ai.test.offline"));
            return;
        }
        // Ask the saved provider directly: no cache, so a fixed key or model shows up at once,
        // and no mock fallback, so a failure shows the provider's error instead of mock text.
        Optional<AiService> provider;
        try {
            provider = AiServiceFactory.createUncachedProvider(settingsService);
        } catch (RuntimeException e) {
            // Reading the saved settings can fail; report it here instead of on the FX thread.
            context.errors().logFailure(tr("ai.test.failed.title"), e);
            statusLabel.setText(tr("ai.test.failed", UiErrors.rootMessage(e)));
            return;
        }
        if (provider.isEmpty()) {
            statusLabel.setText(tr("ai.test.notConfigured"));
            return;
        }
        AiService uncachedProvider = provider.get();
        WordCard sample = WordCard.createNew(context.decks().currentId(), "lucid", "清晰的; 易懂的");
        context.async().run(
            () -> uncachedProvider.explain(sample),
            text -> statusLabel.setText(tr("ai.test.succeeded") + System.lineSeparator() + text),
            error -> statusLabel.setText(tr("ai.test.failed", UiErrors.rootMessage(error))),
            statusLabel,
            tr("ai.test.running"),
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
        apiKeyField.setPromptText(saved ? tr("ai.key.newPrompt") : tr("ai.key.prompt"));
        apiKeyStatus.setText(hint.map(value -> value.length() > 4 ? tr("ai.key.savedEndsWith", value)
            : tr("ai.key.saved")).orElse(""));
    }

    private static void setShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    private void removeKey() {
        if (!context.dialogs().confirm(tr("ai.key.remove.title"), tr("ai.key.remove.header"),
            tr("ai.key.remove.content"))) {
            return;
        }
        context.errors().guard(tr("ai.key.remove.failed"), () -> {
            settingsService.removeAiApiKey();
            replacingKey = false;
            apiKeyField.clear();
            showKeyState();
            reloadAiService();
            statusLabel.setText(Messages.sentences(List.of(tr("ai.key.removed"), aiStatusText())));
        });
    }

    /** Deletes every cached explanation, so each word is explained afresh the next time. */
    private void clearCache() {
        if (!context.dialogs().confirm(tr("ai.cache.clear"), tr("ai.cache.clear.header"),
            tr("ai.cache.clear.content"))) {
            return;
        }
        try {
            int deleted = aiCacheRepository.deleteAll();
            statusLabel.setText(tr("ai.cache.cleared", deleted));
        } catch (SQLException e) {
            context.errors().reportFailure(tr("ai.cache.clear.failed"), e);
        }
    }

    private void reloadAiService() {
        configured.reloadAi();
        context.changes().publish(DataChange.SETTINGS);
    }

    private String aiStatusText() {
        if (settingsService.isOfflineMode()) {
            return tr("ai.status.offline");
        }
        if (configured.ai().isAvailable()) {
            return tr("ai.status.configured");
        }
        return tr("ai.status.notConfigured");
    }
}
