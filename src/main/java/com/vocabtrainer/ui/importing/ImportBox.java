package com.vocabtrainer.ui.importing;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.service.ImportExportService;
import com.vocabtrainer.service.ImportPreview;
import com.vocabtrainer.service.ImportResult;
import com.vocabtrainer.service.SettingsService;
import com.vocabtrainer.service.WordListOptions;
import com.vocabtrainer.service.csv.WordColumns;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.LatestRequest;
import com.vocabtrainer.ui.OtherDecksQuestion;
import com.vocabtrainer.ui.UiAsync;
import com.vocabtrainer.ui.UiErrors;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.vocabtrainer.util.Messages.tr;

/**
 * Imports a word list (GRE CSV, TSV, Anki plain-text export or a list of English words), a legacy
 * txt file or the bundled GRE starter words into the current deck.
 *
 * <p>"Preview and map columns" shows how the file was read and which column holds which field
 * ({@link ColumnMappingPane}); choosing another column previews it again, and the import then uses
 * the chosen columns. The import runs in the background with a progress bar and Cancel while it
 * looks up the meanings of words the file has without one; nothing is imported until it is done.
 */
final class ImportBox {
    private final ViewContext context;
    private final ImportExportService importExportService;
    private final SettingsService settingsService;
    private final TextField importPathField = new TextField();
    private final Label importStatus = new Label();
    private final Button importLegacyButton = new Button(tr("import.legacy"));
    private final Button importCsvButton = new Button(tr("import.wordList"));
    private final Button previewCsvButton = new Button(tr("import.preview"));
    private final Button importStarterButton = new Button(tr("import.starter"));
    private final CheckBox onlineLookup = new CheckBox(tr("import.onlineLookup"));
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Button cancelButton = new Button(tr("import.cancel"));
    private final HBox progressRow;
    private final ColumnMappingPane mapping;
    private final LatestRequest previews = new LatestRequest();
    private final VBox root;
    /** The file the mapping pane shows, or null. */
    private Path previewedPath;
    /** The columns the import detected in that file; the user's choice differs from them or not. */
    private WordColumns detectedColumns;
    /** True while a word list import runs. */
    private boolean importing;

    ImportBox(ViewContext context, ImportExportService importExportService, SettingsService settingsService) {
        this.context = context;
        this.importExportService = importExportService;
        this.settingsService = settingsService;
        this.mapping = new ColumnMappingPane(this::previewChosenColumns);

        importPathField.setId("importPathField");
        importPathField.setPromptText(tr("import.path.prompt"));
        importPathField.setAccessibleText(tr("import.path.accessible"));
        importPathField.textProperty().addListener((observable, previous, text) -> {
            if (previewedPath != null && !previewedPath.toString().equals(text.trim())) {
                // The columns shown belong to another file.
                previewedPath = null;
                previews.invalidate();
                mapping.hide();
            }
        });
        Button chooseButton = new Button(tr("import.choose"));
        chooseButton.setId("chooseImportFileButton");
        chooseButton.setOnAction(event -> {
            List<FileChooser.ExtensionFilter> filters = List.of(
                new FileChooser.ExtensionFilter(tr("import.filter.files"), "*.txt", "*.csv", "*.tsv"),
                new FileChooser.ExtensionFilter(tr("file.filter.all"), "*.*")
            );
            context.dialogs().chooseOpenFile(context.window().get(), tr("import.choose.title"), filters)
                .ifPresent(file -> importPathField.setText(file.toString()));
        });
        importLegacyButton.setId("importLegacyButton");
        importCsvButton.setId("importCsvButton");
        previewCsvButton.setId("previewCsvButton");
        importStarterButton.setId("importStarterButton");
        importStatus.setId("importStatusLabel");
        importStatus.setWrapText(true);
        onlineLookup.setId("importOnlineLookupCheckBox");
        // The preview says which words would be looked up online.
        onlineLookup.selectedProperty().addListener((observable, was, now) -> previewChosenColumns());
        progressBar.setId("importProgressBar");
        progressBar.setMaxWidth(Double.MAX_VALUE);
        cancelButton.setId("cancelImportButton");

        importLegacyButton.setOnAction(event -> importLegacyTxt());
        importCsvButton.setOnAction(event -> importWordList());
        previewCsvButton.setOnAction(event -> previewDetectedColumns());
        importStarterButton.setOnAction(event -> {
            Deck deck = context.decks().current();
            context.async().run(
                () -> importExportService.importBundledGreStarter(deck.getId()),
                result -> afterImport(result, deck),
                error -> showFailure(tr("import.failed"), error),
                importStatus,
                tr("import.starter.running"),
                importButtons()
            );
        });
        showOfflineMode();
        context.changes().subscribe(changes -> {
            if (changes.contains(DataChange.SETTINGS)) {
                showOfflineMode();
            }
        });

        HBox controls = new HBox(10, importPathField, chooseButton, importLegacyButton, previewCsvButton,
            importCsvButton, importStarterButton);
        controls.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(importPathField, Priority.ALWAYS);
        progressRow = new HBox(10, progressBar, cancelButton);
        progressRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(progressBar, Priority.ALWAYS);
        showProgressRow(false);
        root = new VBox(10, Widgets.sectionTitle(tr("import.title")), controls, onlineLookup, mapping.root(), progressRow,
            importStatus);
    }

    Node root() {
        return root;
    }

    /** Disabled while an import or preview runs, so the same file cannot be imported twice at once. */
    private Node[] importButtons() {
        return new Node[] {importLegacyButton, importCsvButton, previewCsvButton, importStarterButton};
    }

    /** Online lookups are never made in offline mode, so the box is off and disabled while it is on. */
    private void showOfflineMode() {
        boolean offline;
        try {
            offline = settingsService.isOfflineMode();
        } catch (RuntimeException e) {
            context.errors().logFailure(tr("import.offlineReadFailed"), e);
            offline = false;
        }
        onlineLookup.setDisable(offline || importing);
        if (offline) {
            onlineLookup.setSelected(false);
        }
        onlineLookup.setTooltip(new Tooltip(offline ? tr("import.onlineLookup.offline") : tr("import.onlineLookup.tooltip")));
    }

    /** The file in the path field; empty, with the reason in the status, when there is none. */
    private Optional<Path> chosenFile(String missingMessage) {
        String text = importPathField.getText() == null ? "" : importPathField.getText().trim();
        if (text.isEmpty()) {
            importStatus.setText(missingMessage);
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(text));
        } catch (InvalidPathException e) {
            importStatus.setText(tr("import.badPath", text));
            return Optional.empty();
        }
    }

    private void importLegacyTxt() {
        Optional<Path> chosen = chosenFile(tr("import.chooseFirst"));
        if (chosen.isEmpty()) {
            return;
        }
        // The import finishes later: it belongs to the deck that is current now, even if the user
        // switches deck before it is done.
        Deck deck = context.decks().current();
        context.async().run(
            () -> importExportService.importLegacyTxt(chosen.get(), deck.getId()),
            result -> afterImport(result, deck),
            error -> showFailure(tr("import.failed"), error),
            importStatus,
            tr("import.running"),
            importButtons()
        );
    }

    /** Previews the file with the columns the import detects, and shows them for the user to change. */
    private void previewDetectedColumns() {
        chosenFile(tr("import.chooseWordListFirst")).ifPresent(path -> preview(path, null));
    }

    /** Previews the shown file again with the columns chosen in the mapping pane. */
    private void previewChosenColumns() {
        if (previewedPath != null && !importing) {
            preview(previewedPath, mapping);
        }
    }

    /** {@code chosen} is null to detect the columns of a new file. */
    private void preview(Path path, ColumnMappingPane chosen) {
        Deck deck = context.decks().current();
        WordListOptions options = new WordListOptions(chosen == null ? null : chosenColumns(),
            onlineLookup.isSelected());
        long ticket = previews.next();
        context.async().run(
            () -> importExportService.previewWordList(path, deck.getId(), options),
            preview -> {
                if (previews.isLatest(ticket)) {
                    showPreview(path, deck, preview, chosen == null);
                }
            },
            error -> {
                if (!previews.isLatest(ticket)) {
                    return;
                }
                if (chosen == null) {
                    mapping.hide();
                    previewedPath = null;
                    showFailure(tr("import.preview.failed"), error);
                } else {
                    // A column choice that cannot be imported, such as no English column.
                    importStatus.setText(tr("import.deck", deck.getName()) + System.lineSeparator() + message(error));
                }
            },
            importStatus,
            tr("import.analyzing"),
            importButtons()
        );
    }

    /** The columns chosen in the mapping pane; null while they are the detected ones. */
    private WordColumns chosenColumns() {
        WordColumns chosen = mapping.chosenColumns();
        return chosen.equals(detectedColumns) ? null : chosen;
    }

    private void showPreview(Path path, Deck deck, ImportPreview preview, boolean newFile) {
        previewedPath = path;
        if (newFile) {
            detectedColumns = preview.mapping();
        }
        mapping.show(preview, newFile);
        importStatus.setText(tr("import.deck", deck.getName()) + System.lineSeparator() + preview.toSummary());
    }

    /**
     * Imports the file in the path field, with the columns chosen in the preview when it shows this
     * file, otherwise with the detected columns. The file is read first: when other active decks
     * already have some of its words, the user chooses whether to copy their meanings, keep the
     * file's or skip them, or cancels.
     */
    private void importWordList() {
        Optional<Path> chosen = chosenFile(tr("import.chooseFirst"));
        if (chosen.isEmpty()) {
            return;
        }
        Path path = chosen.get();
        Deck deck = context.decks().current();
        boolean mapped = mapping.isShown() && path.equals(previewedPath);
        WordListOptions options = new WordListOptions(mapped ? chosenColumns() : null, onlineLookup.isSelected());
        previews.invalidate();
        context.async().run(
            () -> importExportService.previewWordList(path, deck.getId(), options),
            preview -> {
                if (preview.inOtherDecks() == 0) {
                    startImport(path, deck, options);
                    return;
                }
                OtherDecksQuestion.ask(context, preview.inOtherDecks(), preview.otherDeckIds()).ifPresentOrElse(
                    choice -> startImport(path, deck, options.with(choice)),
                    () -> importStatus.setText(tr("import.canceled")));
            },
            error -> showFailure(tr("import.failed"), error),
            importStatus,
            tr("import.analyzing"),
            importButtons()
        );
    }

    /** Runs the import of {@code path} into {@code deck} in the background, with progress and Cancel. */
    private void startImport(Path path, Deck deck, WordListOptions options) {
        AtomicBoolean cancelRequested = new AtomicBoolean();
        String startMessage = tr("import.running");
        Task<ImportResult> task = new Task<>() {
            @Override
            protected ImportResult call() {
                return importExportService.importWordList(path, deck.getId(), options, progress -> {
                    updateProgress(progress.lookedUp(), progress.toLookUp());
                    updateMessage(progress.toDisplayText());
                }, cancelRequested::get);
            }
        };
        importStatus.setText(startMessage);
        task.messageProperty().addListener((observable, previous, progressMessage) -> {
            if (!cancelRequested.get() && progressMessage != null && !progressMessage.isBlank()) {
                importStatus.setText(startMessage + System.lineSeparator() + progressMessage);
            }
        });
        progressBar.progressProperty().bind(task.progressProperty());
        cancelButton.setDisable(false);
        cancelButton.setOnAction(event -> {
            // The import stops before its next lookup; nothing is written until every lookup is done.
            cancelRequested.set(true);
            cancelButton.setDisable(true);
            importStatus.setText(tr("import.canceling"));
        });
        showProgressRow(true);
        for (Node button : importButtons()) {
            button.setDisable(true);
        }
        // The import has its columns and lookup choice; changing them now would only preview again.
        importing = true;
        mapping.root().setDisable(true);
        onlineLookup.setDisable(true);
        task.setOnSucceeded(event -> {
            importFinished();
            mapping.hide();
            previewedPath = null;
            afterImport(task.getValue(), deck);
        });
        task.setOnFailed(event -> {
            importFinished();
            Throwable error = task.getException();
            if (error instanceof CancellationException) {
                importStatus.setText(tr("import.canceled"));
                return;
            }
            context.errors().logFailure(tr("import.failed"), error);
            showFailure(tr("import.failed"), error);
        });
        Thread thread = new Thread(task, UiAsync.THREAD_NAME);
        thread.setDaemon(true);
        thread.start();
    }

    private void importFinished() {
        progressBar.progressProperty().unbind();
        showProgressRow(false);
        for (Node button : importButtons()) {
            button.setDisable(false);
        }
        importing = false;
        mapping.root().setDisable(false);
        showOfflineMode();
    }

    private void showProgressRow(boolean visible) {
        progressRow.setVisible(visible);
        progressRow.setManaged(visible);
        progressBar.setVisible(visible);
        cancelButton.setVisible(visible);
    }

    /**
     * Shows the service's own message, which names the file and line and the reason, rather than
     * only the innermost cause (for an undecodable file that is just "Input length = 1").
     */
    private void showFailure(String title, Throwable error) {
        String message = message(error);
        importStatus.setText(tr("import.failure", title, message));
        context.errors().showError(title, message);
    }

    private static String message(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
            ? UiErrors.rootMessage(error)
            : error.getMessage();
    }

    /** Shows the summary for {@code deck}, the deck the words went into, whichever deck is current now. */
    private void afterImport(ImportResult result, Deck deck) {
        // The import itself has finished; show its summary even if refreshing the views fails. Importing
        // earns no XP and no new words: a word counts as new on the day of its first review.
        importStatus.setText(tr("import.deck", deck.getName()) + System.lineSeparator() + result.toSummary());
        context.errors().guard(tr("import.refreshFailed"),
            () -> context.changes().publish(DataChange.WORDS));
    }
}
