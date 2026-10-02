package com.vocabtrainer.ui.importing;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.service.ImportExportService;
import com.vocabtrainer.service.ImportResult;
import com.vocabtrainer.ui.DataChange;
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
    private final TextField importPathField = new TextField();
    private final Label importStatus = new Label();
    private final Button importLegacyButton = new Button("Import legacy txt");
    private final Button importCsvButton = new Button("Import GRE CSV");
    private final Button previewCsvButton = new Button("Preview GRE CSV");
    private final Button importStarterButton = new Button("Import GRE starter deck");
    private final VBox root;

    ImportBox(ViewContext context, ImportExportService importExportService) {
        this.context = context;
        this.importExportService = importExportService;

        importPathField.setId("importPathField");
        importPathField.setPromptText("Choose legacy txt or GRE CSV");
        Button chooseButton = new Button("Choose file");
        chooseButton.setId("chooseImportFileButton");
        chooseButton.setOnAction(event -> {
            List<FileChooser.ExtensionFilter> filters = List.of(
                new FileChooser.ExtensionFilter("Import Files", "*.txt", "*.csv", "*.tsv"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
            );
            context.dialogs().chooseOpenFile(context.window().get(), "Choose import file", filters)
                .ifPresent(file -> importPathField.setText(file.toString()));
        });
        importLegacyButton.setId("importLegacyButton");
        importCsvButton.setId("importCsvButton");
        previewCsvButton.setId("previewCsvButton");
        importStarterButton.setId("importStarterButton");
        importStatus.setId("importStatusLabel");
        importStatus.setWrapText(true);

        importLegacyButton.setOnAction(event -> importFromPath(true));
        importCsvButton.setOnAction(event -> importFromPath(false));
        previewCsvButton.setOnAction(event -> previewGreCsv());
        importStarterButton.setOnAction(event -> {
            Deck deck = context.decks().current();
            context.async().run(
                () -> importExportService.importBundledGreStarter(deck.getId()),
                result -> afterImport(result, deck),
                error -> showFailure("Import failed", error),
                importStatus,
                "Importing GRE starter deck...",
                importButtons()
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

    /** Disabled while an import or preview runs, so the same file cannot be imported twice at once. */
    private Node[] importButtons() {
        return new Node[] {importLegacyButton, importCsvButton, previewCsvButton, importStarterButton};
    }

    private void importFromPath(boolean legacy) {
        if (importPathField.getText().isBlank()) {
            importStatus.setText("Please choose an import file first.");
            return;
        }
        try {
            Path path = Path.of(importPathField.getText().trim());
            // The import finishes later: it belongs to the deck that is current now, even if the
            // user switches deck before it is done.
            Deck deck = context.decks().current();
            context.async().run(
                () -> legacy
                    ? importExportService.importLegacyTxt(path, deck.getId())
                    : importExportService.importGreCsv(path, deck.getId()),
                result -> afterImport(result, deck),
                error -> showFailure("Import failed", error),
                importStatus,
                "Importing...",
                importButtons()
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
            Deck deck = context.decks().current();
            context.async().run(
                () -> importExportService.previewGreCsv(path, deck.getId()),
                preview -> importStatus.setText("Deck: " + deck.getName() + System.lineSeparator() + preview.toSummary()),
                error -> showFailure("Preview failed", error),
                importStatus,
                "Analyzing CSV...",
                importButtons()
            );
        } catch (RuntimeException e) {
            context.errors().reportFailure("Preview failed", e);
        }
    }

    /**
     * Shows the service's own message, which names the file and line and the reason, rather than
     * only the innermost cause (for an undecodable file that is just "Input length = 1").
     */
    private void showFailure(String title, Throwable error) {
        String message = error.getMessage() == null || error.getMessage().isBlank()
            ? UiErrors.rootMessage(error)
            : error.getMessage();
        importStatus.setText(title + ": " + message);
        context.errors().showError(title, message);
    }

    /** Shows the summary for {@code deck}, the deck the words went into, whichever deck is current now. */
    private void afterImport(ImportResult result, Deck deck) {
        // The import itself has finished; show its summary even if refreshing the views fails. Importing
        // earns no XP and no new words: a word counts as new on the day of its first review.
        importStatus.setText("Deck: " + deck.getName() + System.lineSeparator() + result.toSummary());
        context.errors().guard("Import finished, but refreshing the views failed",
            () -> context.changes().publish(DataChange.WORDS));
    }
}
