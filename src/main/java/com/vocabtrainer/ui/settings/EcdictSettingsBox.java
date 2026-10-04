package com.vocabtrainer.ui.settings;

import com.vocabtrainer.domain.EcdictMetadata;
import com.vocabtrainer.service.LocalDictionaryService;
import com.vocabtrainer.service.SettingsService;
import com.vocabtrainer.service.ecdict.EcdictImportService;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.UiAsync;
import com.vocabtrainer.ui.UiErrors;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import com.vocabtrainer.util.Messages;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.vocabtrainer.util.Messages.tr;

/**
 * Chooses, tests, imports and clears the local ECDICT CSV used as the first dictionary.
 *
 * <p>The CSV is imported once into its own SQLite file ({@link EcdictImportService}); the import runs
 * in the background with a progress bar and can be canceled, and lookups use the previous dictionary
 * until it is done. Opening the window only compares the saved CSV's size and modification time
 * with what was imported, and imports again when they changed.
 */
final class EcdictSettingsBox {
    /** Used when no path is saved, as in earlier versions. */
    static final String PATH_ENVIRONMENT_VARIABLE = "ECDICT_CSV_PATH";

    private final ViewContext context;
    private final SettingsService settingsService;
    private final EcdictImportService importService;
    private final LocalDictionaryService localDictionary;
    private final TextField pathField = new TextField();
    private final Label statusLabel = new Label();
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Button cancelButton = new Button(tr("import.cancel"));
    private final HBox progressRow;
    private final List<Button> actionButtons;
    private final VBox root;

    EcdictSettingsBox(ViewContext context, SettingsService settingsService, EcdictImportService importService,
                      LocalDictionaryService localDictionary) {
        this.context = context;
        this.settingsService = settingsService;
        this.importService = importService;
        this.localDictionary = localDictionary;

        pathField.setText(settingsService.getEcdictPath().orElse(""));
        pathField.setId("ecdictPathField");
        pathField.setPromptText(tr("ecdict.path.prompt"));
        pathField.setAccessibleText(tr("ecdict.path.accessible"));
        statusLabel.setId("ecdictStatusLabel");
        statusLabel.setWrapText(true);
        progressBar.setId("ecdictProgressBar");
        progressBar.setMaxWidth(Double.MAX_VALUE);
        cancelButton.setId("cancelEcdictImportButton");

        Button chooseButton = new Button(tr("ecdict.choose"));
        chooseButton.setId("chooseEcdictButton");
        chooseButton.setOnAction(event -> {
            List<FileChooser.ExtensionFilter> filters = List.of(
                new FileChooser.ExtensionFilter("CSV", "*.csv"),
                new FileChooser.ExtensionFilter(tr("file.filter.all"), "*.*")
            );
            context.dialogs().chooseOpenFile(context.window().get(), tr("ecdict.choose"), filters)
                .ifPresent(file -> pathField.setText(file.toString()));
        });

        Button testButton = new Button(tr("ecdict.test"));
        testButton.setId("testEcdictButton");
        testButton.setOnAction(event -> chosenFile().ifPresent(csv -> context.async().run(
            () -> importService.check(csv),
            check -> statusLabel.setText(tr("ecdict.test.only") + System.lineSeparator() + check.toDisplayText()),
            error -> statusLabel.setText(tr("ecdict.test.failed", message(error))),
            statusLabel,
            tr("ecdict.test.running", csv.toString()),
            testButton
        )));

        Button saveButton = new Button(tr("ecdict.save"));
        saveButton.setId("saveEcdictButton");
        saveButton.setOnAction(event -> saveAndImport(false));

        Button reimportButton = new Button(tr("ecdict.reimport"));
        reimportButton.setId("reimportEcdictButton");
        reimportButton.setOnAction(event -> saveAndImport(true));

        Button clearButton = new Button(tr("ecdict.clear"));
        clearButton.setId("clearEcdictButton");
        clearButton.setOnAction(event -> context.errors().guard(tr("ecdict.clear.failed"), () -> {
            settingsService.clearEcdictPath();
            importService.delete();
            pathField.clear();
            context.changes().publish(DataChange.SETTINGS);
            statusLabel.setText(tr("ecdict.cleared"));
        }));
        actionButtons = List.of(testButton, saveButton, reimportButton, clearButton);

        HBox pathRow = new HBox(10, pathField, chooseButton, testButton);
        HBox.setHgrow(pathField, Priority.ALWAYS);
        HBox actionRow = new HBox(10, saveButton, reimportButton, clearButton);
        progressRow = new HBox(10, progressBar, cancelButton);
        HBox.setHgrow(progressBar, Priority.ALWAYS);
        showProgressRow(false);
        root = new VBox(10, Widgets.sectionTitle(tr("ecdict.title")), pathRow, actionRow, progressRow,
            statusLabel);

        statusLabel.setText(importedText());
        importIfChanged();
    }

    Node root() {
        return root;
    }

    /**
     * Imports the saved CSV (or the one {@value #PATH_ENVIRONMENT_VARIABLE} names) when it was never
     * imported or changed since; otherwise the CSV is not touched.
     */
    private void importIfChanged() {
        Optional<String> configured = settingsService.getEcdictPath()
            .or(() -> Optional.ofNullable(System.getenv(PATH_ENVIRONMENT_VARIABLE)).filter(value -> !value.isBlank()));
        if (configured.isEmpty()) {
            return;
        }
        try {
            Path csv = Path.of(configured.get().trim());
            switch (importService.state(csv)) {
                case UP_TO_DATE -> {
                    // The imported dictionary is current.
                }
                case NOT_IMPORTED -> startImport(csv, tr("ecdict.importing", csv.toString()), false);
                case CHANGED -> startImport(csv, tr("ecdict.importing.changed", csv.toString()), false);
                case FILE_MISSING -> statusLabel.setText(tr("ecdict.notFound", csv.toString()) + System.lineSeparator()
                    + importedText());
            }
        } catch (RuntimeException e) {
            context.errors().logFailure(tr("ecdict.check.failed.title"), e);
            statusLabel.setText(tr("ecdict.check.failed", message(e)));
        }
    }

    /**
     * Imports the chosen file, unless it is already imported and unchanged and {@code force} is
     * false, and saves its path once it is imported. A failed or canceled import keeps the previous
     * path and dictionary.
     */
    private void saveAndImport(boolean force) {
        Optional<Path> chosen = chosenFile();
        if (chosen.isEmpty()) {
            return;
        }
        Path csv = chosen.get();
        context.errors().guard(tr("ecdict.savePath.failed"), () -> {
            if (!force && importService.state(csv) == EcdictImportService.State.UP_TO_DATE) {
                savePath(csv);
                statusLabel.setText(tr("ecdict.upToDate") + System.lineSeparator() + importedText());
                return;
            }
            startImport(csv, tr("ecdict.importing", csv.toString()), true);
        });
    }

    private void savePath(Path csv) {
        settingsService.saveEcdictPath(csv.toString());
        context.changes().publish(DataChange.SETTINGS);
    }

    /** The file in the path field; empty, with the reason in the status, when there is none. */
    private Optional<Path> chosenFile() {
        String text = pathField.getText() == null ? "" : pathField.getText().trim();
        if (text.isEmpty()) {
            statusLabel.setText(tr("ecdict.chooseFirst"));
            return Optional.empty();
        }
        Path csv;
        try {
            csv = Path.of(text);
        } catch (InvalidPathException e) {
            statusLabel.setText(tr("import.badPath", text));
            return Optional.empty();
        }
        if (!Files.isRegularFile(csv)) {
            statusLabel.setText(tr("ecdict.notFound", csv.toString()));
            return Optional.empty();
        }
        return Optional.of(csv);
    }

    /** Imports in the background; {@code savePath} saves the path when the import succeeds. */
    private void startImport(Path csv, String startMessage, boolean savePath) {
        AtomicBoolean cancelRequested = new AtomicBoolean();
        Task<EcdictMetadata> task = new Task<>() {
            @Override
            protected EcdictMetadata call() throws Exception {
                return importService.importCsv(csv, progress -> {
                    // Indeterminate while the encoding check reads the file before the first row.
                    updateProgress(progress.rows() == 0 ? -1 : progress.bytesRead(), progress.totalBytes());
                    updateMessage(progress.toDisplayText());
                }, cancelRequested::get);
            }
        };
        statusLabel.setText(startMessage);
        task.messageProperty().addListener((observable, oldMessage, progressMessage) -> {
            if (!cancelRequested.get() && progressMessage != null && !progressMessage.isBlank()) {
                statusLabel.setText(startMessage + System.lineSeparator() + progressMessage);
            }
        });
        progressBar.progressProperty().bind(task.progressProperty());
        cancelButton.setDisable(false);
        cancelButton.setOnAction(event -> {
            // The import stops at its next check; the buttons come back when it has cleaned up.
            cancelRequested.set(true);
            cancelButton.setDisable(true);
            statusLabel.setText(tr("import.canceling"));
        });
        showProgressRow(true);
        actionButtons.forEach(button -> button.setDisable(true));

        String notSaved = savePath ? tr("ecdict.pathNotSaved") : "";
        task.setOnSucceeded(event -> {
            importFinished();
            EcdictMetadata imported = task.getValue();
            statusLabel.setText(tr("ecdict.imported", imported.rowCount(),
                String.format(Locale.ROOT, "%.1f", imported.durationMillis() / 1000.0))
                + System.lineSeparator() + importedText());
            if (savePath) {
                context.errors().guard(tr("ecdict.savePath.failed"), () -> savePath(csv));
            }
        });
        task.setOnFailed(event -> {
            importFinished();
            Throwable error = task.getException();
            if (error instanceof CancellationException) {
                statusLabel.setText(Messages.sentences(List.of(tr("ecdict.import.canceled"), notSaved))
                    + System.lineSeparator() + importedText());
                return;
            }
            context.errors().logFailure(tr("ecdict.import.failed.title"), error);
            statusLabel.setText(tr("ecdict.import.failed", message(error)) + System.lineSeparator()
                + (savePath ? notSaved + System.lineSeparator() : "") + importedText());
        });
        Thread thread = new Thread(task, UiAsync.THREAD_NAME);
        thread.setDaemon(true);
        thread.start();
    }

    private void importFinished() {
        progressBar.progressProperty().unbind();
        showProgressRow(false);
        actionButtons.forEach(button -> button.setDisable(false));
    }

    private void showProgressRow(boolean visible) {
        progressRow.setVisible(visible);
        progressRow.setManaged(visible);
        progressBar.setVisible(visible);
        cancelButton.setVisible(visible);
    }

    /** What lookups use now, read from the imported dictionary's metadata (never from the CSV). */
    private String importedText() {
        try {
            return localDictionary.status().toDisplayText();
        } catch (RuntimeException e) {
            context.errors().logFailure(tr("ecdict.read.failed"), e);
            return tr("ecdict.error.read", message(e));
        }
    }

    /** The exception's own message, which names the file and line; the root cause's when it has none. */
    private static String message(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? UiErrors.rootMessage(error) : message;
    }
}
