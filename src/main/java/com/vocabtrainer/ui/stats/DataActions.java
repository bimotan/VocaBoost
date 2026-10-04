package com.vocabtrainer.ui.stats;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.service.BackupRestoreResult;
import com.vocabtrainer.service.BackupService;
import com.vocabtrainer.service.ExamPlanService;
import com.vocabtrainer.service.GoalService;
import com.vocabtrainer.service.StatsService;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.UiErrors;
import com.vocabtrainer.ui.ViewContext;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Labeled;
import javafx.stage.FileChooser;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.vocabtrainer.util.Messages.tr;

/** Report and CSV exports, and JSON backup export and restore. */
final class DataActions {
    private static final Logger LOGGER = Logger.getLogger(DataActions.class.getName());

    private final ViewContext context;
    private final StatsService statsService;
    private final GoalService goalService;
    private final BackupService backupService;
    private final ExamPlanService examPlanService;
    private final Labeled status;

    /** {@code status} shows progress while an export or restore runs. */
    DataActions(ViewContext context, StatsService statsService, GoalService goalService, BackupService backupService,
                ExamPlanService examPlanService, Labeled status) {
        this.context = context;
        this.statsService = statsService;
        this.goalService = goalService;
        this.backupService = backupService;
        this.examPlanService = examPlanService;
        this.status = status;
    }

    List<Button> exportButtons() {
        return List.of(
            button("exportReportButton", tr("data.report"), this::exportReport),
            button("exportWordsCsvButton", tr("data.wordsCsv"), this::exportWordsCsv),
            button("exportReviewLogsCsvButton", tr("data.reviewLogsCsv"), this::exportReviewLogsCsv),
            button("exportBackupButton", tr("data.backup.export"), this::exportJsonBackup),
            button("importBackupButton", tr("data.backup.import"), this::importJsonBackup)
        );
    }

    private static Button button(String id, String text, Runnable action) {
        Button button = new Button(text);
        button.setId(id);
        button.setOnAction(event -> action.run());
        return button;
    }

    private void exportReport() {
        Optional<Path> file = context.dialogs().chooseSaveFile(context.window().get(), tr("data.report.title"),
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
            context.errors().showInfo(tr("data.report.done", exported.toAbsolutePath().toString()));
        } catch (RuntimeException e) {
            context.errors().reportFailure(tr("export.failed"), e);
        }
    }

    private void exportWordsCsv() {
        exportFile(tr("data.wordsCsv"), "vocaboost-words.csv", "CSV", "*.csv", backupService::exportWordsCsv);
    }

    private void exportReviewLogsCsv() {
        exportFile(tr("data.reviewLogsCsv"), "vocaboost-review-logs.csv", "CSV", "*.csv",
            backupService::exportReviewLogsCsv);
    }

    private void exportJsonBackup() {
        exportFile(tr("data.backup.export"), "vocaboost-backup.json", "JSON", "*.json", backupService::exportJsonBackup);
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
            exported -> context.errors().showInfo(tr("export.done", exported.toAbsolutePath().toString())),
            error -> context.errors().showError(tr("export.failed"), UiErrors.rootMessage(error)),
            status,
            tr("export.running")
        );
    }

    private void importJsonBackup() {
        Optional<Path> file = context.dialogs().chooseOpenFile(context.window().get(), tr("data.backup.import"),
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
            () -> {
                BackupRestoreResult result = backupService.importJsonBackup(file.get(), targetDeck.getId(), policy.get());
                bringReviewsBeforeExams();
                return result;
            },
            result -> afterRestore(result, targetDeck),
            error -> context.errors().showError(tr("import.failed"), UiErrors.rootMessage(error)),
            status,
            tr("data.backup.importing")
        );
    }

    /** The backup may schedule reviews on or after the exam; they come before it, as at startup. */
    private void bringReviewsBeforeExams() {
        try {
            examPlanService.bringReviewsBeforeExams();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Backup restored, but the reviews after the exam were not brought forward", e);
        }
    }

    private Optional<BackupService.ExistingWordPolicy> askExistingWordPolicy(Deck targetDeck) {
        ButtonType keepProgress = new ButtonType(tr("data.backup.keep"), ButtonBar.ButtonData.OK_DONE);
        ButtonType useBackupProgress = new ButtonType(tr("data.backup.useBackup"), ButtonBar.ButtonData.OTHER);
        Optional<ButtonType> choice = context.dialogs().choose(tr("data.backup.import"),
            tr("data.backup.question", targetDeck.getName()), tr("data.backup.explanation"),
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
        context.errors().guard(tr("data.backup.refreshFailed"),
            () -> context.changes().publish(DataChange.WORDS, DataChange.REVIEWS));
        context.dialogs().showText(tr("data.backup.import"), tr("import.deck", targetDeck.getName()), result.toSummary(),
            result.invalidRows().isEmpty() ? 5 : 12);
    }
}
