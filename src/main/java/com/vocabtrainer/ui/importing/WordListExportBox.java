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

import static com.vocabtrainer.util.Messages.tr;

/**
 * Exports the current deck's words for other apps: an Anki plain-text file (the file Anki's
 * "Import File" reads, with the meaning, part of speech, phonetic, example and tags) and a plain
 * list of the English words. Words CSV, review logs and backups are on the Statistics tab.
 */
final class WordListExportBox {
    private final ViewContext context;
    private final Label status = new Label();
    private final Button ankiButton = new Button(tr("export.anki"));
    private final Button wordListButton = new Button(tr("export.wordList"));
    private final VBox root;

    WordListExportBox(ViewContext context, ImportExportService importExportService) {
        this.context = context;
        ankiButton.setId("exportAnkiButton");
        wordListButton.setId("exportWordListButton");
        status.setId("exportStatusLabel");
        status.setWrapText(true);
        ankiButton.setOnAction(event -> export(tr("export.anki.title"), "vocaboost-anki.txt",
            new FileChooser.ExtensionFilter(tr("export.anki.filter"), "*.txt", "*.tsv"), importExportService::exportForAnki));
        wordListButton.setOnAction(event -> export(tr("export.wordList.title"), "vocaboost-word-list.txt",
            new FileChooser.ExtensionFilter(tr("export.text.filter"), "*.txt"), importExportService::exportWordList));

        Label hint = new Label(tr("export.anki.hint"));
        hint.setWrapText(true);
        HBox buttons = new HBox(10, ankiButton, wordListButton);
        buttons.setAlignment(Pos.CENTER_LEFT);
        root = new VBox(10, Widgets.sectionTitle(tr("export.title")), buttons, hint, status);
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
                status.setText(tr("import.deck", deck.getName()) + System.lineSeparator()
                    + tr("export.done", exported.toAbsolutePath().toString()));
                context.errors().showInfo(tr("export.done", exported.toAbsolutePath().toString()));
            },
            error -> {
                String message = error.getMessage() == null || error.getMessage().isBlank()
                    ? UiErrors.rootMessage(error)
                    : error.getMessage();
                status.setText(tr("import.failure", tr("export.failed"), message));
                context.errors().showError(tr("export.failed"), message);
            },
            status,
            tr("export.running"),
            ankiButton, wordListButton
        );
    }
}
