package com.vocabtrainer.ui.importing;

import com.vocabtrainer.service.LocalDictionaryService;
import com.vocabtrainer.service.LocalDictionaryStatus;
import com.vocabtrainer.service.SettingsService;
import com.vocabtrainer.ui.ConfiguredServices;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.time.LocalDateTime;
import java.util.List;

/** Chooses, tests, saves and clears the local ECDICT CSV used as the first dictionary. */
final class EcdictSettingsBox {
    private final VBox root;

    EcdictSettingsBox(ViewContext context, SettingsService settingsService, ConfiguredServices configured) {
        TextField pathField = new TextField(settingsService.getEcdictPath().orElse(""));
        pathField.setId("ecdictPathField");
        pathField.setPromptText("Choose local ECDICT CSV");
        Label statusLabel = new Label(new LocalDictionaryService(pathField.getText()).status().toDisplayText());
        statusLabel.setId("ecdictStatusLabel");
        statusLabel.setWrapText(true);

        Button chooseButton = new Button("Choose ECDICT CSV");
        chooseButton.setId("chooseEcdictButton");
        chooseButton.setOnAction(event -> {
            List<FileChooser.ExtensionFilter> filters = List.of(
                new FileChooser.ExtensionFilter("CSV", "*.csv"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
            );
            context.dialogs().chooseOpenFile(context.window().get(), "Choose ECDICT CSV", filters)
                .ifPresent(file -> pathField.setText(file.toString()));
        });

        Button testButton = new Button("Test ECDICT");
        testButton.setId("testEcdictButton");
        testButton.setOnAction(event -> {
            LocalDictionaryStatus status = new LocalDictionaryService(pathField.getText()).status();
            statusLabel.setText(status.toDisplayText()
                + (status.configuredPathLoaded() ? System.lineSeparator() + "Configured CSV loaded." : ""));
        });

        Button saveButton = new Button("Save Dictionary Path");
        saveButton.setId("saveEcdictButton");
        saveButton.setOnAction(event -> context.errors().guard("Save dictionary path failed", () -> {
            settingsService.saveEcdictPath(pathField.getText());
            LocalDictionaryService local = new LocalDictionaryService(pathField.getText());
            LocalDictionaryStatus status = local.status();
            settingsService.save(SettingsService.ECDICT_LAST_LOADED_COUNT_KEY, String.valueOf(status.loadedCount()));
            settingsService.save(SettingsService.ECDICT_LAST_LOADED_AT_KEY, LocalDateTime.now().toString());
            configured.reloadDictionary();
            context.changes().publish(DataChange.SETTINGS);
            statusLabel.setText("Saved. " + status.toDisplayText());
        }));

        Button clearButton = new Button("Clear Dictionary Path");
        clearButton.setId("clearEcdictButton");
        clearButton.setOnAction(event -> context.errors().guard("Clear dictionary path failed", () -> {
            settingsService.clearEcdictPath();
            pathField.clear();
            configured.reloadDictionary();
            context.changes().publish(DataChange.SETTINGS);
            statusLabel.setText("Cleared. Using bundled starter and online fallback.");
        }));

        HBox controls = new HBox(10, pathField, chooseButton, testButton, saveButton, clearButton);
        HBox.setHgrow(pathField, Priority.ALWAYS);
        root = new VBox(10, Widgets.sectionTitle("ECDICT Local Dictionary"), controls, statusLabel);
    }

    Node root() {
        return root;
    }
}
