package com.vocabtrainer.ui.importing;

import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.service.DictionaryService;
import com.vocabtrainer.service.WordValidationService;
import com.vocabtrainer.ui.ConfiguredServices;
import com.vocabtrainer.ui.UiErrors;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/** Looks a word up in the configured dictionaries; choosing a result fills the add form. */
final class DictionaryLookupBox {
    private final ViewContext context;
    private final WordValidationService validationService;
    private final ConfiguredServices configured;
    private final VBox root;

    DictionaryLookupBox(ViewContext context, WordValidationService validationService, ConfiguredServices configured,
                        Consumer<DictionaryEntry> onChosen) {
        this.context = context;
        this.validationService = validationService;
        this.configured = configured;

        TextField lookupField = new TextField();
        lookupField.setId("lookupField");
        lookupField.setPromptText("Enter an English word to look up");
        Button lookupButton = new Button("Lookup online");
        lookupButton.setId("lookupButton");
        Button refreshLookupButton = new Button("Refresh cache");
        refreshLookupButton.setId("refreshLookupButton");
        Label lookupStatus = new Label();
        lookupStatus.setId("lookupStatusLabel");
        lookupStatus.setWrapText(true);
        ListView<DictionaryEntry> results = new ListView<>();
        results.setId("lookupResults");
        results.setPrefHeight(120);
        results.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(DictionaryEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    String meaning = item.chinese() == null || item.chinese().isBlank()
                        ? "English definition: " + item.definition()
                        : item.chinese();
                    setText(item.english() + " | " + meaning + " | " + item.source());
                }
            }
        });
        results.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, entry) -> {
            if (entry != null) {
                onChosen.accept(entry);
            }
        });
        Button[] lookupButtons = {lookupButton, refreshLookupButton};
        lookupButton.setOnAction(event -> runLookup(lookupField, lookupButtons, lookupStatus, results, false));
        refreshLookupButton.setOnAction(event -> runLookup(lookupField, lookupButtons, lookupStatus, results, true));
        HBox controls = new HBox(10, lookupField, lookupButton, refreshLookupButton);
        HBox.setHgrow(lookupField, Priority.ALWAYS);
        root = new VBox(10, Widgets.sectionTitle("Dictionary Lookup"), controls, results, lookupStatus);
    }

    Node root() {
        return root;
    }

    /** Looks up the word; both lookup buttons are disabled until it is done, so results cannot overtake each other. */
    private void runLookup(TextField lookupField, Button[] lookupButtons, Label lookupStatus,
                           ListView<DictionaryEntry> results, boolean refresh) {
        try {
            String english = validationService.validateEnglishOnly(lookupField.getText());
            DictionaryService dictionary = configured.dictionary();
            context.async().run(
                () -> refresh ? dictionary.refresh(english) : dictionary.lookup(english),
                result -> {
                    lookupStatus.setText(result.success()
                        ? result.message()
                        : "词条未找到。" + System.lineSeparator() + result.message());
                    results.getItems().setAll(result.entries());
                    if (!result.entries().isEmpty()) {
                        results.getSelectionModel().selectFirst();
                    }
                },
                error -> lookupStatus.setText("查词失败：" + UiErrors.rootMessage(error)),
                lookupStatus,
                refresh ? "Refreshing dictionary cache..." : "Looking up...",
                lookupButtons
            );
        } catch (IllegalArgumentException e) {
            lookupStatus.setText(e.getMessage());
        }
    }
}
