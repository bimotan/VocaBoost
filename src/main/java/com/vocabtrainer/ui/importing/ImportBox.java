package com.vocabtrainer.ui.importing;

import com.vocabtrainer.domain.Achievement;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.GoalUpdate;
import com.vocabtrainer.service.AchievementService;
import com.vocabtrainer.service.GoalService;
import com.vocabtrainer.service.ImportExportService;
import com.vocabtrainer.service.ImportResult;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.Formats;
import com.vocabtrainer.ui.UiErrors;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.nio.file.Path;
import java.util.List;

/** Imports a legacy txt file, a GRE CSV or the bundled GRE starter words into the current deck. */
final class ImportBox {
    private final ViewContext context;
    private final ImportExportService importExportService;
    private final GoalService goalService;
    private final AchievementService achievementService;
    private final TextField importPathField = new TextField();
    private final Label importStatus = new Label();
    private final VBox root;

    ImportBox(ViewContext context, ImportExportService importExportService, GoalService goalService,
              AchievementService achievementService) {
        this.context = context;
        this.importExportService = importExportService;
        this.goalService = goalService;
        this.achievementService = achievementService;

        importPathField.setId("importPathField");
        importPathField.setPromptText("Choose legacy txt or GRE CSV");
        Button chooseButton = new Button("Choose file");
        chooseButton.setId("chooseImportFileButton");
        chooseButton.setOnAction(event -> {
            List<FileChooser.ExtensionFilter> filters = List.of(
                new FileChooser.ExtensionFilter("Import Files", "*.txt", "*.csv"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
            );
            context.dialogs().chooseOpenFile(context.window().get(), "Choose import file", filters)
                .ifPresent(file -> importPathField.setText(file.toString()));
        });
        Button importLegacyButton = new Button("Import legacy txt");
        importLegacyButton.setId("importLegacyButton");
        Button importCsvButton = new Button("Import GRE CSV");
        importCsvButton.setId("importCsvButton");
        Button previewCsvButton = new Button("Preview GRE CSV");
        previewCsvButton.setId("previewCsvButton");
        Button importStarterButton = new Button("Import GRE starter deck");
        importStarterButton.setId("importStarterButton");
        importStatus.setId("importStatusLabel");
        importStatus.setWrapText(true);

        importLegacyButton.setOnAction(event -> importFromPath(true));
        importCsvButton.setOnAction(event -> importFromPath(false));
        previewCsvButton.setOnAction(event -> previewGreCsv());
        importStarterButton.setOnAction(event -> {
            long deckId = context.decks().currentId();
            context.async().run(
                () -> importExportService.importBundledGreStarter(deckId),
                this::afterImport,
                error -> context.errors().showError("Import failed", UiErrors.rootMessage(error)),
                importStatus,
                "Importing GRE starter deck...",
                importStarterButton
            );
        });

        HBox controls = new HBox(10, importPathField, chooseButton, importLegacyButton, previewCsvButton,
            importCsvButton, importStarterButton);
        controls.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(importPathField, Priority.ALWAYS);
        root = new VBox(10, Widgets.sectionTitle("Import"), controls, importStatus);
    }

    Node root() {
        return root;
    }

    private void importFromPath(boolean legacy) {
        if (importPathField.getText().isBlank()) {
            importStatus.setText("Please choose an import file first.");
            return;
        }
        try {
            Path path = Path.of(importPathField.getText().trim());
            long deckId = context.decks().currentId();
            context.async().run(
                () -> legacy
                    ? importExportService.importLegacyTxt(path, deckId)
                    : importExportService.importGreCsv(path, deckId),
                this::afterImport,
                error -> context.errors().showError("Import failed", UiErrors.rootMessage(error)),
                importStatus,
                "Importing..."
            );
        } catch (RuntimeException e) {
            context.errors().reportFailure("Import failed", e);
        }
    }

    private void previewGreCsv() {
        if (importPathField.getText().isBlank()) {
            importStatus.setText("Please choose a GRE CSV file first.");
            return;
        }
        try {
            Path path = Path.of(importPathField.getText().trim());
            long deckId = context.decks().currentId();
            context.async().run(
                () -> importExportService.previewGreCsv(path, deckId),
                preview -> importStatus.setText("Deck: " + context.decks().current().getName()
                    + System.lineSeparator() + preview.toSummary()),
                error -> context.errors().showError("Preview failed", UiErrors.rootMessage(error)),
                importStatus,
                "Analyzing CSV..."
            );
        } catch (RuntimeException e) {
            context.errors().reportFailure("Preview failed", e);
        }
    }

    private void afterImport(ImportResult result) {
        Deck deck = context.decks().current();
        // The import itself has finished; show its summary even if the progress update below fails.
        String summary = "Deck: " + deck.getName() + System.lineSeparator() + result.toSummary();
        importStatus.setText(summary);
        context.errors().guard("Import finished, but updating progress failed", () -> {
            GoalUpdate update = goalService.recordNewWords(deck.getId(), result.importedCount());
            List<Achievement> unlocked = achievementService.evaluate(deck.getId(), update.progress(), false,
                update.dailyGoalCompleted());
            importStatus.setText(summary + Formats.unlockedSuffix(unlocked));
            context.changes().publish(DataChange.WORDS);
        });
    }
}
