package com.vocabtrainer.ui.stats;

import com.vocabtrainer.domain.DailyReviewStat;
import com.vocabtrainer.service.BackupService;
import com.vocabtrainer.service.GoalService;
import com.vocabtrainer.service.StatsService;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.Formats;
import com.vocabtrainer.ui.LazyRefresh;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;
import java.util.stream.Collectors;

/** The Statistics tab: review charts, memory distribution, hardest words, exports and backups. */
public final class StatisticsView {
    private final ViewContext context;
    private final StatsService statsService;
    private final DoubleSupplier desiredRetention;
    private final BarChart<String, Number> reviewCountChart;
    private final LineChart<String, Number> accuracyChart;
    private final PieChart memoryChart = new PieChart();
    private final Label overdueStatsLabel = new Label("-");
    private final TextArea hardestWordsArea = new TextArea();
    private final TextArea analyticsArea = new TextArea();
    private final Tab tab;
    private final LazyRefresh lazy;

    /** {@code desiredRetention} is the scheduler's, which the Settings tab changes. */
    public StatisticsView(ViewContext context, StatsService statsService, GoalService goalService,
                          BackupService backupService, DoubleSupplier desiredRetention) {
        this.context = context;
        this.statsService = statsService;
        this.desiredRetention = desiredRetention;

        CategoryAxis reviewDateAxis = new CategoryAxis();
        reviewDateAxis.setTickLabelRotation(-35);
        reviewCountChart = new BarChart<>(reviewDateAxis, new NumberAxis());
        reviewCountChart.setId("reviewCountChart");
        reviewCountChart.setTitle("Daily review count");
        reviewCountChart.setLegendVisible(false);
        reviewCountChart.setAnimated(false);
        reviewCountChart.setPrefHeight(280);
        reviewCountChart.setMinHeight(280);

        CategoryAxis accuracyDateAxis = new CategoryAxis();
        accuracyDateAxis.setTickLabelRotation(-35);
        accuracyChart = new LineChart<>(accuracyDateAxis, new NumberAxis(0, 1, 0.25));
        accuracyChart.setId("accuracyChart");
        accuracyChart.setTitle("Accuracy trend");
        accuracyChart.setLegendVisible(false);
        accuracyChart.setAnimated(false);
        accuracyChart.setPrefHeight(280);
        accuracyChart.setMinHeight(280);

        memoryChart.setId("memoryChart");
        memoryChart.setTitle("Memory: chance of recall now");
        memoryChart.setPrefHeight(260);
        memoryChart.setLabelsVisible(false);
        memoryChart.setAnimated(false);

        overdueStatsLabel.setId("overdueStatsLabel");
        overdueStatsLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: 600;");
        hardestWordsArea.setId("hardestWordsArea");
        hardestWordsArea.setEditable(false);
        hardestWordsArea.setWrapText(true);
        hardestWordsArea.setPrefRowCount(8);
        analyticsArea.setId("analyticsArea");
        analyticsArea.setEditable(false);
        analyticsArea.setWrapText(true);
        analyticsArea.setPrefRowCount(8);

        Button refreshButton = new Button("Refresh statistics");
        refreshButton.setId("refreshStatisticsButton");
        refreshButton.setOnAction(event -> context.errors().guard("Refresh statistics failed", this::refreshNow));
        DataActions actions = new DataActions(context, statsService, goalService, backupService, overdueStatsLabel);
        List<Button> exportButtons = new ArrayList<>();
        exportButtons.add(refreshButton);
        exportButtons.addAll(actions.exportButtons());
        HBox buttons = new HBox(10);
        buttons.getChildren().setAll(exportButtons);
        VBox charts = new VBox(16, reviewCountChart, accuracyChart, memoryChart, overdueStatsLabel,
            Widgets.sectionTitle("Hardest Words"), hardestWordsArea,
            Widgets.sectionTitle("Portfolio Summary"), analyticsArea, buttons);
        charts.setId("statisticsCharts");
        charts.setPadding(new Insets(24));
        ScrollPane scrollPane = new ScrollPane(charts);
        scrollPane.setFitToWidth(true);
        tab = Widgets.tab("statisticsTab", "Statistics", scrollPane);
        // The charts depend on the clock (a 7-day window, due counts), so every visit recomputes them.
        lazy = new LazyRefresh(tab, this::refresh, context.errors(), "Refresh statistics failed", true);
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

    private void refreshNow() {
        lazy.refreshNow();
    }

    private void refresh() {
        long deckId = context.decks().currentId();
        List<DailyReviewStat> dailyStats = statsService.dailyReviewStats(deckId, 7);
        List<String> dayCategories = dailyStats.stream()
            .map(stat -> stat.date().getMonthValue() + "/" + stat.date().getDayOfMonth())
            .toList();
        ((CategoryAxis) reviewCountChart.getXAxis()).setCategories(FXCollections.observableArrayList(dayCategories));
        ((CategoryAxis) accuracyChart.getXAxis()).setCategories(FXCollections.observableArrayList(dayCategories));
        XYChart.Series<String, Number> reviewSeries = new XYChart.Series<>();
        XYChart.Series<String, Number> accuracySeries = new XYChart.Series<>();
        for (int i = 0; i < dailyStats.size(); i++) {
            DailyReviewStat stat = dailyStats.get(i);
            String day = dayCategories.get(i);
            reviewSeries.getData().add(new XYChart.Data<>(day, stat.reviewCount()));
            accuracySeries.getData().add(new XYChart.Data<>(day, stat.accuracy()));
        }
        reviewCountChart.getData().setAll(List.of(reviewSeries));
        accuracyChart.getData().setAll(List.of(accuracySeries));

        List<PieChart.Data> memoryData = statsService.memoryDistribution(deckId).stream()
            .filter(stat -> stat.count() > 0)
            .map(stat -> new PieChart.Data(stat.label(), stat.count()))
            .toList();
        memoryChart.getData().setAll(memoryData);
        overdueStatsLabel.setText("Overdue or due words: " + statsService.overdueCount(deckId));

        String hardest = statsService.hardestWords(deckId, 8).stream()
            .map(word -> word.english() + " | avg similarity " + Formats.percent(word.averageSimilarity())
                + " | Again " + word.againCount())
            .collect(Collectors.joining(System.lineSeparator()));
        hardestWordsArea.setText(hardest.isBlank() ? "No review logs yet." : hardest);
        analyticsArea.setText("Spaced repetition: FSRS-5 models each word's stability and difficulty and schedules"
            + " the next review when the chance of recall drops to " + Formats.percent(desiredRetention.getAsDouble())
            + " (the desired retention on the Settings tab)."
            + System.lineSeparator() + "Retrieval practice: every review stores the typed answer and the time taken to answer;"
            + " failed and new words come back after short learning steps in the same session."
            + System.lineSeparator() + "Adaptive scheduling: an answer that is not similar enough counts as Again;"
            + " words that lapse 8 times are tagged as leeches."
            + System.lineSeparator() + "Learning analytics: charts summarize volume, accuracy, chance of recall and hard words.");
    }
}
