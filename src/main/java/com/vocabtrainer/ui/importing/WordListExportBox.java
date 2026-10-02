package com.vocabtrainer.ui.importing;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.service.ImportExportService;
import com.vocabtrainer.ui.UiErrors;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Exports the current deck's words for other apps: an Anki plain-text file (the file Anki's
 * "Import File" reads, with the meaning, part of speech, phonetic, example and tags) and a plain
 * list of the English words. Words CSV, review logs and backups are on the Statistics tab.
 */
final class WordListExportBox {
    private final ViewContext context;
    private final Label status = new Label();
    private final Button ankiButton = new Button("Export for Anki (TSV)");
    private final Button wordListButton = new Button("Export word list (txt)");
    private final VBox root;

    WordListExportBox(ViewContext context, ImportExportService importExportService) {
        this.context = context;
        ankiButton.setId("exportAnkiButton");
        wordListButton.setId("exportWordListButton");
        status.setId("exportStatusLabel");
        status.setWrapText(true);
        ankiButton.setOnAction(event -> export("Export for Anki", "vocaboost-anki.txt",
            new FileChooser.ExtensionFilter("Anki plain text", "*.txt", "*.tsv"), importExportService::exportForAnki));
        wordListButton.setOnAction(event -> export("Export word list", "vocaboost-word-list.txt",
            new FileChooser.ExtensionFilter("Text", "*.txt"), importExportService::exportWordList));

        Label hint = new Label("Anki: File > Import, then choose the file; the columns, tags and HTML are set by the file.");
        hint.setWrapText(true);
        HBox buttons = new HBox(10, ankiButton, wordListButton);
        buttons.setAlignment(Pos.CENTER_LEFT);
        root = new VBox(10, Widgets.sectionTitle("Export for Other Apps"), buttons, hint, status);
    }

    Node root() {
        return root;
    }

    /** Exports the deck that is current when the user picks the file; the export runs in the background. */
    private void export(String title, String fileName, FileChooser.ExtensionFilter filter,
                        BiFunction<Long, Path, Path> exporter) {
        Optional<Path> file = context.dialogs().chooseSaveFile(context.window().get(), title, fileName, List.of(filter));
        if (file.isEmpty()) {
            return;
        }
        Deck deck = context.decks().current();
        Path output = file.get();
        context.async().run(
            () -> exporter.apply(deck.getId(), output),
            exported -> {
                status.setText("Deck: " + deck.getName() + System.lineSeparator() + "Exported: "
                    + exported.toAbsolutePath());
                context.errors().showInfo("Exported: " + exported.toAbsolutePath());
            },
            error -> {
                String message = error.getMessage() == null || error.getMessage().isBlank()
                    ? UiErrors.rootMessage(error)
                    : error.getMessage();
                status.setText("Export failed: " + message);
                context.errors().showError("Export failed", message);
            },
            status,
            "Exporting...",
            ankiButton, wordListButton
        );
    }
}
