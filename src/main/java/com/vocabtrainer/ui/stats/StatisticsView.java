package com.vocabtrainer.ui.stats;

import com.vocabtrainer.domain.DailyReviewStat;
import com.vocabtrainer.service.BackupService;
import com.vocabtrainer.service.ExamPlanService;
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

import static com.vocabtrainer.util.Messages.tr;

/**
 * The Statistics tab: the workload forecast, review charts, memory distribution, hardest words,
 * exports and backups.
 */
public final class StatisticsView {
    private final ViewContext context;
    private final StatsService statsService;
    private final WorkloadForecastChart forecast;
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
                          BackupService backupService, ExamPlanService examPlanService,
                          DoubleSupplier desiredRetention) {
        this.context = context;
        this.statsService = statsService;
        this.desiredRetention = desiredRetention;
        this.forecast = new WorkloadForecastChart(context, statsService, examPlanService);

        CategoryAxis reviewDateAxis = new CategoryAxis();
        reviewDateAxis.setTickLabelRotation(-35);
        reviewCountChart = new BarChart<>(reviewDateAxis, new NumberAxis());
        reviewCountChart.setId("reviewCountChart");
        reviewCountChart.setTitle(tr("stats.chart.reviews"));
        reviewCountChart.setLegendVisible(false);
        reviewCountChart.setAnimated(false);
        reviewCountChart.setPrefHeight(280);
        reviewCountChart.setMinHeight(280);

        CategoryAxis accuracyDateAxis = new CategoryAxis();
        accuracyDateAxis.setTickLabelRotation(-35);
        accuracyChart = new LineChart<>(accuracyDateAxis, new NumberAxis(0, 1, 0.25));
        accuracyChart.setId("accuracyChart");
        accuracyChart.setTitle(tr("stats.chart.accuracy"));
        accuracyChart.setLegendVisible(false);
        accuracyChart.setAnimated(false);
        accuracyChart.setPrefHeight(280);
        accuracyChart.setMinHeight(280);

        memoryChart.setId("memoryChart");
        memoryChart.setTitle(tr("stats.chart.memory"));
        memoryChart.setPrefHeight(260);
        memoryChart.setLabelsVisible(false);
        memoryChart.setAnimated(false);

        overdueStatsLabel.setId("overdueStatsLabel");
        overdueStatsLabel.getStyleClass().add("sub-stat-value");
        hardestWordsArea.setId("hardestWordsArea");
        hardestWordsArea.setEditable(false);
        hardestWordsArea.setWrapText(true);
        hardestWordsArea.setPrefRowCount(8);
        hardestWordsArea.setAccessibleText(tr("stats.hardest.accessible"));
        analyticsArea.setId("analyticsArea");
        analyticsArea.setEditable(false);
        analyticsArea.setWrapText(true);
        analyticsArea.setPrefRowCount(8);
        analyticsArea.setAccessibleText(tr("stats.summary.accessible"));

        Button refreshButton = new Button(tr("stats.refresh"));
        refreshButton.setId("refreshStatisticsButton");
        refreshButton.setOnAction(event -> context.errors().guard(tr("stats.refreshFailed"), this::refreshNow));
        DataActions actions = new DataActions(context, statsService, goalService, backupService, examPlanService,
            overdueStatsLabel);
        List<Button> exportButtons = new ArrayList<>();
        exportButtons.add(refreshButton);
        exportButtons.addAll(actions.exportButtons());
        HBox buttons = new HBox(10);
        buttons.getChildren().setAll(exportButtons);
        VBox charts = new VBox(16, forecast.root(), reviewCountChart, accuracyChart, memoryChart, overdueStatsLabel,
            Widgets.sectionTitle(tr("stats.hardest.title")), hardestWordsArea,
            Widgets.sectionTitle(tr("stats.summary.title")), analyticsArea, buttons);
        charts.setId("statisticsCharts");
        charts.setPadding(new Insets(24));
        ScrollPane scrollPane = new ScrollPane(charts);
        scrollPane.setFitToWidth(true);
        tab = Widgets.tab("statisticsTab", tr("stats.tab"), scrollPane);
        // The charts depend on the clock (a 7-day window, due counts), so every visit recomputes them.
        lazy = new LazyRefresh(tab, this::refresh, context.errors(), tr("stats.refreshFailed"), true);
        context.changes().subscribe(changes -> {
            if (changes.contains(DataChange.WORDS) || changes.contains(DataChange.REVIEWS)
                || changes.contains(DataChange.REVIEW_SETTINGS)) {
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
        forecast.refresh(deckId);
        List<DailyReviewStat> dailyStats = statsService.dailyReviewStats(deckId, 7);
        List<String> dayCategories = dailyStats.stream()
            .map(stat -> Formats.shortDate(stat.date()))
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
        overdueStatsLabel.setText(tr("stats.overdue", statsService.overdueCount(deckId)));

        String hardest = statsService.hardestWords(deckId, 8).stream()
            .map(word -> tr("stats.hardest.word", word.english(), Formats.percent(word.averageSimilarity()),
                word.againCount()))
            .collect(Collectors.joining(System.lineSeparator()));
        hardestWordsArea.setText(hardest.isBlank() ? tr("stats.hardest.none") : hardest);
        analyticsArea.setText(String.join(System.lineSeparator(),
            tr("stats.summary.spacedRepetition", Formats.percent(desiredRetention.getAsDouble())),
            tr("stats.summary.retrievalPractice"),
            tr("stats.summary.adaptiveScheduling"),
            tr("stats.summary.analytics")));
    }
}
