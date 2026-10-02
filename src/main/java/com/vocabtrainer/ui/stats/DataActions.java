package com.vocabtrainer.ui.stats;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.service.BackupRestoreResult;
import com.vocabtrainer.service.BackupService;
import com.vocabtrainer.service.GoalService;
import com.vocabtrainer.service.StatsService;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.UiErrors;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.util.AppLogging;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Labeled;
import javafx.stage.FileChooser;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Report and CSV exports, JSON backup export and restore, and opening the data and log folders. */
final class DataActions {
    private static final Logger LOGGER = Logger.getLogger(DataActions.class.getName());

    private final ViewContext context;
    private final StatsService statsService;
    private final GoalService goalService;
    private final BackupService backupService;
    private final Path databasePath;
    private final Labeled status;

    /** {@code status} shows progress while an export or restore runs. */
    DataActions(ViewContext context, StatsService statsService, GoalService goalService, BackupService backupService,
                Path databasePath, Labeled status) {
        this.context = context;
        this.statsService = statsService;
        this.goalService = goalService;
        this.backupService = backupService;
        this.databasePath = databasePath;
        this.status = status;
    }

    List<Button> exportButtons() {
        return List.of(
            button("exportReportButton", "Export Markdown report", this::exportReport),
            button("exportWordsCsvButton", "Export words CSV", this::exportWordsCsv),
            button("exportReviewLogsCsvButton", "Export review logs CSV", this::exportReviewLogsCsv),
            button("exportBackupButton", "Export JSON backup", this::exportJsonBackup),
            button("importBackupButton", "Import JSON backup", this::importJsonBackup)
        );
    }

    List<Button> folderButtons() {
        return List.of(
            button("openDataFolderButton", "Open data folder", this::openDataFolder),
            button("openLogFolderButton", "Open log folder", this::openLogFolder)
        );
    }

    private static Button button(String id, String text, Runnable action) {
        Button button = new Button(text);
        button.setId(id);
        button.setOnAction(event -> action.run());
        return button;
    }

    private void exportReport() {
        Optional<Path> file = context.dialogs().chooseSaveFile(context.window().get(), "Export learning report",
            "vocaboost-learning-report.md", List.of(new FileChooser.ExtensionFilter("Markdown", "*.md")));
        if (file.isEmpty()) {
            return;
        }
        try {
            Deck deck = context.decks().current();
            Path exported = statsService.exportMarkdownReport(
                deck.getId(),
                deck.getName(),
                goalService.getTodayProgress(deck.getId()),
                file.get()
            );
            context.errors().showInfo("Report exported: " + exported.toAbsolutePath());
        } catch (RuntimeException e) {
            context.errors().reportFailure("Export failed", e);
        }
    }

    private void exportWordsCsv() {
        exportFile("Export words CSV", "vocaboost-words.csv", "CSV", "*.csv", backupService::exportWordsCsv);
    }

    private void exportReviewLogsCsv() {
        exportFile("Export review logs CSV", "vocaboost-review-logs.csv", "CSV", "*.csv",
            backupService::exportReviewLogsCsv);
    }

    private void exportJsonBackup() {
        exportFile("Export JSON backup", "vocaboost-backup.json", "JSON", "*.json", backupService::exportJsonBackup);
    }

    /** Exports the deck that is current when the user picks the file; the export runs in the background. */
    private void exportFile(String title, String fileName, String extensionName, String extension,
                            BiFunction<Long, Path, Path> exporter) {
        Optional<Path> file = context.dialogs().chooseSaveFile(context.window().get(), title, fileName,
            List.of(new FileChooser.ExtensionFilter(extensionName, extension)));
        if (file.isEmpty()) {
            return;
        }
        long deckId = context.decks().currentId();
        Path output = file.get();
        context.async().run(
            () -> exporter.apply(deckId, output),
            exported -> context.errors().showInfo("Exported: " + exported.toAbsolutePath()),
            error -> context.errors().showError("Export failed", UiErrors.rootMessage(error)),
            status,
            "Exporting..."
        );
    }

    private void importJsonBackup() {
        Optional<Path> file = context.dialogs().chooseOpenFile(context.window().get(), "Import JSON backup",
            List.of(new FileChooser.ExtensionFilter("JSON", "*.json")));
        if (file.isEmpty()) {
            return;
        }
        Deck targetDeck = context.decks().current();
        Optional<BackupService.ExistingWordPolicy> policy = askExistingWordPolicy(targetDeck);
        if (policy.isEmpty()) {
            return;
        }
        context.async().run(
            () -> backupService.importJsonBackup(file.get(), targetDeck.getId(), policy.get()),
            result -> afterRestore(result, targetDeck),
            error -> context.errors().showError("Import failed", UiErrors.rootMessage(error)),
            status,
            "Importing backup..."
        );
    }

    private Optional<BackupService.ExistingWordPolicy> askExistingWordPolicy(Deck targetDeck) {
        ButtonType keepProgress = new ButtonType("Keep current progress", ButtonBar.ButtonData.OK_DONE);
        ButtonType useBackupProgress = new ButtonType("Use backup progress", ButtonBar.ButtonData.OTHER);
        Optional<ButtonType> choice = context.dialogs().choose("Import JSON backup",
            "Restore the backup into " + targetDeck.getName() + "?",
            "Words missing from this deck are added with the review schedule saved in the backup. "
                + "For words already in the deck, keep their current review progress or replace it with the backup's.",
            keepProgress, useBackupProgress, ButtonType.CANCEL);
        if (choice.isEmpty() || choice.get() == ButtonType.CANCEL) {
            return Optional.empty();
        }
        return Optional.of(choice.get() == useBackupProgress
            ? BackupService.ExistingWordPolicy.OVERWRITE_SCHEDULE
            : BackupService.ExistingWordPolicy.KEEP_SCHEDULE);
    }

    private void afterRestore(BackupRestoreResult result, Deck targetDeck) {
        // A restore brings back saved history; unlike adding words it earns no XP or new-word credit.
        context.errors().guard("Backup restored, but refreshing the views failed",
            () -> context.changes().publish(DataChange.WORDS, DataChange.REVIEWS));
        context.dialogs().showText("Import JSON backup", "Deck: " + targetDeck.getName(), result.toSummary(),
            result.invalidRows().isEmpty() ? 5 : 12);
    }

    private void openDataFolder() {
        openFolder("Data folder", databasePath.toAbsolutePath().getParent());
    }

    private void openLogFolder() {
        Optional<Path> logDirectory = AppLogging.logDirectory();
        if (logDirectory.isEmpty()) {
            context.errors().showInfo("File logging is unavailable; logs are written to the console only.");
            return;
        }
        openFolder("Log folder", logDirectory.get());
    }

    private void openFolder(String name, Path folder) {
        if (folder == null) {
            context.errors().showInfo(name + " is unavailable.");
            return;
        }
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                context.errors().showInfo(name + ": " + folder);
                return;
            }
            Desktop.getDesktop().open(folder.toFile());
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Cannot open " + folder, e);
            context.errors().showError("Open folder failed", UiErrors.rootMessage(e) + System.lineSeparator() + name + ": " + folder);
        }
    }
}
