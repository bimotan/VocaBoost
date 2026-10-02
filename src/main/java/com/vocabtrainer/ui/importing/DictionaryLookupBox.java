package com.vocabtrainer.ui.importing;

import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.domain.LookupOutcome;
import com.vocabtrainer.service.DictionaryService;
import com.vocabtrainer.service.WordValidationService;
import com.vocabtrainer.ui.ConfiguredServices;
import com.vocabtrainer.ui.LatestRequest;
import com.vocabtrainer.ui.UiAsync;
import com.vocabtrainer.ui.UiErrors;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * Looks a word up in the configured dictionaries; choosing a result fills the add form. The lookup
 * runs in the background and the buttons stay usable: a new lookup, or a change to the word,
 * cancels the one still running, and a result for a word no longer asked for is dropped.
 */
final class DictionaryLookupBox {
    private final ViewContext context;
    private final WordValidationService validationService;
    private final ConfiguredServices configured;
    private final TextField lookupField = new TextField();
    private final Button retryButton = new Button("Retry");
    private final ProgressIndicator busyIndicator = LookupMessages.busyIndicator("lookupBusyIndicator");
    private final Label lookupStatus = new Label();
    private final ListView<DictionaryEntry> results = new ListView<>();
    private final LatestRequest lookups = new LatestRequest();
    private final VBox root;
    private UiAsync.Cancellable running;
    private boolean lastWasRefresh;

    DictionaryLookupBox(ViewContext context, WordValidationService validationService, ConfiguredServices configured,
                        Consumer<DictionaryEntry> onChosen) {
        this.context = context;
        this.validationService = validationService;
        this.configured = configured;

        lookupField.setId("lookupField");
        lookupField.setPromptText("Enter an English word to look up");
        Button lookupButton = new Button("Lookup online");
        lookupButton.setId("lookupButton");
        Button refreshLookupButton = new Button("Refresh cache");
        refreshLookupButton.setId("refreshLookupButton");
        retryButton.setId("lookupRetryButton");
        retryButton.managedProperty().bind(retryButton.visibleProperty());
        retryButton.setVisible(false);
        lookupStatus.setId("lookupStatusLabel");
        lookupStatus.setWrapText(true);
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
        lookupField.textProperty().addListener((obs, oldText, newText) -> {
            if (running != null) {
                cancelRunning();
                lookupStatus.setText("Lookup canceled: the word changed.");
            }
            retryButton.setVisible(false);
        });
        lookupField.setOnAction(event -> runLookup(false));
        lookupButton.setOnAction(event -> runLookup(false));
        refreshLookupButton.setOnAction(event -> runLookup(true));
        retryButton.setOnAction(event -> runLookup(lastWasRefresh));
        HBox controls = new HBox(10, lookupField, lookupButton, refreshLookupButton, retryButton, busyIndicator);
        controls.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(lookupField, Priority.ALWAYS);
        root = new VBox(10, Widgets.sectionTitle("Dictionary Lookup"), controls, results, lookupStatus);
    }

    Node root() {
        return root;
    }

    /** Looks up the word in the field, cancelling a lookup that is still running. */
    private void runLookup(boolean refresh) {
        String english;
        try {
            english = validationService.validateEnglishOnly(lookupField.getText());
        } catch (IllegalArgumentException e) {
            lookupStatus.setText(e.getMessage());
            return;
        }
        cancelRunning();
        long ticket = lookups.next();
        lastWasRefresh = refresh;
        retryButton.setVisible(false);
        LookupMessages.setBusy(busyIndicator, true);
        lookupStatus.setText(refresh ? "Refreshing dictionary cache..." : "Looking up " + english + "...");
        DictionaryService dictionary = configured.dictionary();
        running = context.async().start(
            () -> refresh ? dictionary.refresh(english) : dictionary.lookup(english),
            result -> {
                if (lookups.isLatest(ticket)) {
                    finished();
                    show(result);
                }
            },
            error -> {
                if (lookups.isLatest(ticket)) {
                    finished();
                    context.errors().logFailure("Dictionary lookup failed", error);
                    lookupStatus.setText("查词失败：" + UiErrors.rootMessage(error));
                    retryButton.setVisible(true);
                }
            });
    }

    private void show(DictionaryLookupResult result) {
        if (result.success()) {
            lookupStatus.setText(result.message());
            results.getItems().setAll(result.entries());
            results.getSelectionModel().selectFirst();
            return;
        }
        results.getItems().clear();
        lookupStatus.setText(LookupMessages.headline(result.outcome()) + System.lineSeparator() + result.message());
        // Asking again only helps when the dictionaries may answer next time, not in offline mode.
        retryButton.setVisible(result.unavailable() && result.outcome() != LookupOutcome.OFFLINE);
    }

    private void cancelRunning() {
        if (running != null) {
            running.cancel();
            lookups.invalidate();
            finished();
        }
    }

    private void finished() {
        running = null;
        LookupMessages.setBusy(busyIndicator, false);
    }
}
