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
    private final Button cancelButton = new Button("Cancel Import");
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
        pathField.setPromptText("Choose local ECDICT CSV");
        statusLabel.setId("ecdictStatusLabel");
        statusLabel.setWrapText(true);
        progressBar.setId("ecdictProgressBar");
        progressBar.setMaxWidth(Double.MAX_VALUE);
        cancelButton.setId("cancelEcdictImportButton");

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
        testButton.setOnAction(event -> chosenFile().ifPresent(csv -> context.async().run(
            () -> importService.check(csv),
            check -> statusLabel.setText("Test only, nothing imported." + System.lineSeparator() + check.toDisplayText()),
            error -> statusLabel.setText("Test failed: " + message(error)),
            statusLabel,
            "Testing " + csv + "...",
            testButton
        )));

        Button saveButton = new Button("Save and Import");
        saveButton.setId("saveEcdictButton");
        saveButton.setOnAction(event -> saveAndImport(false));

        Button reimportButton = new Button("Re-import");
        reimportButton.setId("reimportEcdictButton");
        reimportButton.setOnAction(event -> saveAndImport(true));

        Button clearButton = new Button("Clear Dictionary Path");
        clearButton.setId("clearEcdictButton");
        clearButton.setOnAction(event -> context.errors().guard("Clear dictionary path failed", () -> {
            settingsService.clearEcdictPath();
            importService.delete();
            pathField.clear();
            context.changes().publish(DataChange.SETTINGS);
            statusLabel.setText("Cleared. Using bundled starter and online fallback.");
        }));
        actionButtons = List.of(testButton, saveButton, reimportButton, clearButton);

        HBox pathRow = new HBox(10, pathField, chooseButton, testButton);
        HBox.setHgrow(pathField, Priority.ALWAYS);
        HBox actionRow = new HBox(10, saveButton, reimportButton, clearButton);
        progressRow = new HBox(10, progressBar, cancelButton);
        HBox.setHgrow(progressBar, Priority.ALWAYS);
        showProgressRow(false);
        root = new VBox(10, Widgets.sectionTitle("ECDICT Local Dictionary"), pathRow, actionRow, progressRow,
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
                case NOT_IMPORTED -> startImport(csv, "Importing " + csv + "...", false);
                case CHANGED -> startImport(csv, "The ECDICT CSV changed since it was imported; importing "
                    + csv + " again...", false);
                case FILE_MISSING -> statusLabel.setText("ECDICT CSV not found: " + csv + System.lineSeparator()
                    + importedText());
            }
        } catch (RuntimeException e) {
            context.errors().logFailure("Checking the ECDICT dictionary failed", e);
            statusLabel.setText("Cannot check the ECDICT dictionary: " + message(e));
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
        context.errors().guard("Save dictionary path failed", () -> {
            if (!force && importService.state(csv) == EcdictImportService.State.UP_TO_DATE) {
                savePath(csv);
                statusLabel.setText("Saved. Already imported, the file has not changed." + System.lineSeparator()
                    + importedText());
                return;
            }
            startImport(csv, "Importing " + csv + "...", true);
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
            statusLabel.setText("Choose an ECDICT CSV first.");
            return Optional.empty();
        }
        Path csv;
        try {
            csv = Path.of(text);
        } catch (InvalidPathException e) {
            statusLabel.setText("Not a valid file path: " + text);
            return Optional.empty();
        }
        if (!Files.isRegularFile(csv)) {
            statusLabel.setText("ECDICT CSV not found: " + csv);
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
            statusLabel.setText("Canceling the import...");
        });
        showProgressRow(true);
        actionButtons.forEach(button -> button.setDisable(true));

        String notSaved = savePath ? " The path was not saved." : "";
        task.setOnSucceeded(event -> {
            importFinished();
            EcdictMetadata imported = task.getValue();
            statusLabel.setText(String.format(Locale.ROOT, "Imported %,d entries in %.1f s.",
                imported.rowCount(), imported.durationMillis() / 1000.0) + System.lineSeparator() + importedText());
            if (savePath) {
                context.errors().guard("Save dictionary path failed", () -> savePath(csv));
            }
        });
        task.setOnFailed(event -> {
            importFinished();
            Throwable error = task.getException();
            if (error instanceof CancellationException) {
                statusLabel.setText("Import canceled." + notSaved + System.lineSeparator() + importedText());
                return;
            }
            context.errors().logFailure("ECDICT import failed", error);
            statusLabel.setText("Import failed: " + message(error) + System.lineSeparator()
                + (savePath ? "The path was not saved." + System.lineSeparator() : "") + importedText());
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
            context.errors().logFailure("Reading the ECDICT dictionary failed", e);
            return "Cannot read the imported ECDICT dictionary: " + message(e);
        }
    }

    /** The exception's own message, which names the file and line; the root cause's when it has none. */
    private static String message(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? UiErrors.rootMessage(error) : message;
    }
}
