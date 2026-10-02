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
import com.vocabtrainer.util.DateTimeUtil;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tab;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.List;

/**
 * The Dashboard tab: today's counts, goals, XP and badges for the current deck, and the streak of
 * every deck. Today is the study day; the counts are read from the review logs, like the Statistics
 * tab's. "Edit goals" changes the daily goals and the session goal.
 */
public final class DashboardView {
    private final ViewContext context;
    private final StatsService statsService;
    private final GoalService goalService;
    private final AchievementService achievementService;
    private final Path databasePath;

    private final Label totalWordsLabel = new Label("-");
    private final Label dueTodayLabel = new Label("-");
    private final Label dueReviewsLabel = new Label("-");
    private final Label newAvailableTodayLabel = new Label("-");
    private final Label reviewedTodayLabel = new Label("-");
    private final Label newWordsTodayLabel = new Label("-");
    private final Label accuracyTodayLabel = new Label("-");
    private final Label masteredWordsLabel = new Label("-");
    private final Label streakLabel = new Label("-");
    private final Label xpLabel = new Label("-");
    private final Label badgesLabel = new Label("-");
    private final ProgressBar reviewProgress = new ProgressBar(0);
    private final ProgressBar newWordProgress = new ProgressBar(0);
    /** Whether the current deck has goals of its own or uses the default goals. */
    private final Label goalScopeLabel = new Label();
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
            if (changes.contains(DataChange.WORDS) || changes.contains(DataChange.REVIEWS)
                || changes.contains(DataChange.REVIEW_SETTINGS) || changes.contains(DataChange.GOALS)) {
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
        dueReviewsLabel.setId("dueReviewsLabel");
        newAvailableTodayLabel.setId("newAvailableTodayLabel");
        reviewedTodayLabel.setId("reviewedTodayLabel");
        newWordsTodayLabel.setId("newWordsTodayLabel");
        accuracyTodayLabel.setId("accuracyTodayLabel");
        masteredWordsLabel.setId("masteredWordsLabel");
        streakLabel.setId("streakLabel");
        xpLabel.setId("xpLabel");
        badgesLabel.setId("badgesLabel");
        reviewProgress.setId("reviewGoalProgress");
        newWordProgress.setId("newWordGoalProgress");
        goalScopeLabel.setId("goalScopeLabel");
        addStat(grid, 0, "Total words", totalWordsLabel);
        addStat(grid, 1, "Due today", dueTodayLabel);
        // "Due today" splits into the two queues: due reviews and the new words the daily limit lets in.
        addSubStat(grid, 2, "Due reviews", dueReviewsLabel);
        addSubStat(grid, 3, "New available today", newAvailableTodayLabel);
        addStat(grid, 4, "Reviews today", reviewedTodayLabel);
        addStat(grid, 5, "New words today", newWordsTodayLabel);
        addStat(grid, 6, "Accuracy today", accuracyTodayLabel);
        addStat(grid, 7, "Mastered words", masteredWordsLabel);
        addStat(grid, 8, "Streak (all decks)", streakLabel);
        addStat(grid, 9, "XP", xpLabel);

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

        Button editGoalsButton = new Button("Edit goals");
        editGoalsButton.setId("editGoalsButton");
        // Reading the goals for the form can fail too (the database); that is reported, not thrown at JavaFX.
        editGoalsButton.setOnAction(event -> context.errors().guard("Goals not saved", this::editGoals));
        goalScopeLabel.setStyle("-fx-text-fill: #6b7280;");
        HBox goalsTitle = new HBox(12, Widgets.sectionTitle("Daily Goals"), editGoalsButton, goalScopeLabel);
        goalsTitle.setAlignment(Pos.CENTER_LEFT);

        VBox progressBox = new VBox(10,
            goalsTitle,
            new Label("Review goal"),
            reviewProgress,
            new Label("New-word goal"),
            newWordProgress,
            Widgets.sectionTitle("Unlocked Badges"),
            badgesLabel,
            new HBox(10, refreshButton, dataFolderButton)
        );
        progressBox.setPadding(new Insets(18, 0, 0, 0));
        grid.add(progressBox, 0, 10, 2, 1);
        return grid;
    }

    private static void addStat(GridPane grid, int row, String name, Label valueLabel) {
        Label nameLabel = new Label(name);
        nameLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #4b5563;");
        valueLabel.setStyle("-fx-font-size: 22px; -fx-font-weight: 700;");
        grid.add(nameLabel, 0, row);
        grid.add(valueLabel, 1, row);
    }

    /** A smaller, indented stat that breaks down the one above it. */
    private static void addSubStat(GridPane grid, int row, String name, Label valueLabel) {
        Label nameLabel = new Label(name);
        nameLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #6b7280; -fx-padding: 0 0 0 16;");
        valueLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: 600;");
        grid.add(nameLabel, 0, row);
        grid.add(valueLabel, 1, row);
    }

    private void editGoals() {
        if (new GoalsDialog(context, goalService.settings()).edit(context.decks().current())) {
            context.errors().guard("Goals saved, but refreshing the views failed",
                () -> context.changes().publish(DataChange.GOALS));
        }
    }

    private void refresh() {
        long deckId = context.decks().currentId();
        DashboardStats stats = statsService.dashboardStats(deckId);
        DailyGoalProgress progress = goalService.getTodayProgress(deckId);
        List<Achievement> achievements = achievementService.getUnlockedAchievements(deckId);
        totalWordsLabel.setText(stats.totalWords()
            + (stats.suspendedWords() > 0 ? " (" + stats.suspendedWords() + " suspended)" : ""));
        dueTodayLabel.setText(String.valueOf(stats.dueToday()));
        dueReviewsLabel.setText(String.valueOf(stats.dueReviews()));
        newAvailableTodayLabel.setText(String.valueOf(stats.newAvailableToday()));
        reviewedTodayLabel.setText(progress.reviewedCount() + " / " + progress.reviewGoal());
        newWordsTodayLabel.setText(progress.newWordsCount() + " / " + progress.newWordGoal());
        accuracyTodayLabel.setText(Formats.percent(progress.accuracy()));
        masteredWordsLabel.setText(String.valueOf(stats.masteredWords()));
        streakLabel.setText(DateTimeUtil.days(progress.currentStreak()));
        goalScopeLabel.setText(goalService.settings().deckGoals(deckId).isPresent()
            ? "This deck's own goals" : "Default goals of every deck");
        xpLabel.setText(String.valueOf(progress.totalXp()));
        reviewProgress.setProgress(progress.reviewProgress());
        newWordProgress.setProgress(progress.newWordProgress());
        badgesLabel.setText(Formats.achievementNames(achievements));
    }
}
