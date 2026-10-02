package com.vocabtrainer.ui.dashboard;

import com.vocabtrainer.domain.Achievement;
import com.vocabtrainer.domain.DailyGoalProgress;
import com.vocabtrainer.service.AchievementService;
import com.vocabtrainer.service.DashboardStats;
import com.vocabtrainer.service.GoalService;
import com.vocabtrainer.service.StatsService;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.Folders;
import com.vocabtrainer.ui.Formats;
import com.vocabtrainer.ui.LazyRefresh;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tab;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.List;

/** The Dashboard tab: today's counts, goals, streak, XP and badges for the current deck. */
public final class DashboardView {
    private final ViewContext context;
    private final StatsService statsService;
    private final GoalService goalService;
    private final AchievementService achievementService;
    private final Path databasePath;

    private final Label totalWordsLabel = new Label("-");
    private final Label dueTodayLabel = new Label("-");
    private final Label reviewedTodayLabel = new Label("-");
    private final Label newWordsTodayLabel = new Label("-");
    private final Label accuracyTodayLabel = new Label("-");
    private final Label masteredWordsLabel = new Label("-");
    private final Label streakLabel = new Label("-");
    private final Label xpLabel = new Label("-");
    private final Label badgesLabel = new Label("-");
    private final ProgressBar reviewProgress = new ProgressBar(0);
    private final ProgressBar newWordProgress = new ProgressBar(0);
    private final Tab tab;
    private final LazyRefresh lazy;

    public DashboardView(ViewContext context, StatsService statsService, GoalService goalService,
                         AchievementService achievementService, Path databasePath) {
        this.context = context;
        this.statsService = statsService;
        this.goalService = goalService;
        this.achievementService = achievementService;
        this.databasePath = databasePath;
        this.tab = Widgets.tab("dashboardTab", "Dashboard", createContent());
        this.lazy = new LazyRefresh(tab, this::refresh, context.errors(), "Refresh failed", false);
        context.changes().subscribe(changes -> {
            if (changes.contains(DataChange.WORDS) || changes.contains(DataChange.REVIEWS)) {
                lazy.markStale();
            }
        });
        context.decks().onSwitch(deck -> lazy.markStale());
    }

    public Tab tab() {
        return tab;
    }

    private GridPane createContent() {
        GridPane grid = new GridPane();
        grid.setPadding(new Insets(24));
        grid.setHgap(18);
        grid.setVgap(14);

        totalWordsLabel.setId("totalWordsLabel");
        dueTodayLabel.setId("dueTodayLabel");
        reviewedTodayLabel.setId("reviewedTodayLabel");
        newWordsTodayLabel.setId("newWordsTodayLabel");
        accuracyTodayLabel.setId("accuracyTodayLabel");
        masteredWordsLabel.setId("masteredWordsLabel");
        streakLabel.setId("streakLabel");
        xpLabel.setId("xpLabel");
        badgesLabel.setId("badgesLabel");
        reviewProgress.setId("reviewGoalProgress");
        newWordProgress.setId("newWordGoalProgress");
        addStat(grid, 0, "Total words", totalWordsLabel);
        addStat(grid, 1, "Due now", dueTodayLabel);
        addStat(grid, 2, "Reviews today", reviewedTodayLabel);
        addStat(grid, 3, "New words today", newWordsTodayLabel);
        addStat(grid, 4, "Accuracy today", accuracyTodayLabel);
        addStat(grid, 5, "Mastered words", masteredWordsLabel);
        addStat(grid, 6, "Streak", streakLabel);
        addStat(grid, 7, "XP", xpLabel);

        reviewProgress.setPrefWidth(420);
        newWordProgress.setPrefWidth(420);
        badgesLabel.setWrapText(true);
        Button refreshButton = new Button("Refresh");
        refreshButton.setId("refreshDashboardButton");
        refreshButton.setOnAction(event -> context.errors().guard("Refresh failed", () -> lazy.refreshNow()));
        // The folder's path names the user's account, so it is not shown on screen (or in screenshots).
        Button dataFolderButton = new Button("Open data folder");
        dataFolderButton.setId("dashboardDataFolderButton");
        dataFolderButton.setOnAction(event ->
            Folders.open(context.errors(), "Data folder", databasePath.toAbsolutePath().getParent()));

        VBox progressBox = new VBox(10,
            Widgets.sectionTitle("Daily Goals"),
            new Label("Review goal"),
            reviewProgress,
            new Label("New-word goal"),
            newWordProgress,
            Widgets.sectionTitle("Unlocked Badges"),
            badgesLabel,
            new HBox(10, refreshButton, dataFolderButton)
        );
        progressBox.setPadding(new Insets(18, 0, 0, 0));
        grid.add(progressBox, 0, 8, 2, 1);
        return grid;
    }

    private static void addStat(GridPane grid, int row, String name, Label valueLabel) {
        Label nameLabel = new Label(name);
        nameLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #4b5563;");
        valueLabel.setStyle("-fx-font-size: 22px; -fx-font-weight: 700;");
        grid.add(nameLabel, 0, row);
        grid.add(valueLabel, 1, row);
    }

    private void refresh() {
        long deckId = context.decks().currentId();
        DashboardStats stats = statsService.dashboardStats(deckId);
        DailyGoalProgress progress = goalService.getTodayProgress(deckId);
        List<Achievement> achievements = achievementService.getUnlockedAchievements(deckId);
        totalWordsLabel.setText(String.valueOf(stats.totalWords()));
        dueTodayLabel.setText(String.valueOf(stats.dueToday()));
        reviewedTodayLabel.setText(progress.reviewedCount() + " / " + progress.reviewGoal());
        newWordsTodayLabel.setText(progress.newWordsCount() + " / " + progress.newWordGoal());
        accuracyTodayLabel.setText(Formats.percent(progress.accuracy()));
        masteredWordsLabel.setText(String.valueOf(stats.masteredWords()));
        streakLabel.setText(progress.currentStreak() + " days");
        xpLabel.setText(String.valueOf(progress.totalXp()));
        reviewProgress.setProgress(progress.reviewProgress());
        newWordProgress.setProgress(progress.newWordProgress());
        badgesLabel.setText(Formats.achievementNames(achievements));
    }
}
