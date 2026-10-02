package com.vocabtrainer.ui.stats;

import com.vocabtrainer.domain.DailyReviewStat;
import com.vocabtrainer.service.BackupService;
import com.vocabtrainer.service.GoalService;
import com.vocabtrainer.service.StatsService;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.Formats;
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** The Statistics tab: review charts, memory distribution, hardest words, exports and backups. */
public final class StatisticsView {
    private final ViewContext context;
    private final StatsService statsService;
    private final BarChart<String, Number> reviewCountChart;
    private final LineChart<String, Number> accuracyChart;
    private final PieChart memoryChart = new PieChart();
    private final Label overdueStatsLabel = new Label("-");
    private final TextArea hardestWordsArea = new TextArea();
    private final TextArea analyticsArea = new TextArea();
    private final Tab tab;
    private boolean visited;

    public StatisticsView(ViewContext context, StatsService statsService, GoalService goalService,
                          BackupService backupService, Path databasePath) {
        this.context = context;
        this.statsService = statsService;

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
        memoryChart.setTitle("Memory strength distribution");
        memoryChart.setPrefHeight(260);
        memoryChart.setLabelsVisible(false);

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
        refreshButton.setOnAction(event -> context.errors().guard("Refresh statistics failed", this::refresh));
        DataActions actions = new DataActions(context, statsService, goalService, backupService, databasePath,
            overdueStatsLabel);
        List<Button> exportButtons = new ArrayList<>();
        exportButtons.add(refreshButton);
        exportButtons.addAll(actions.exportButtons());
        HBox buttons = new HBox(10);
        buttons.getChildren().setAll(exportButtons);
        HBox folderButtons = new HBox(10);
        folderButtons.getChildren().setAll(actions.folderButtons());
        VBox charts = new VBox(16, reviewCountChart, accuracyChart, memoryChart, overdueStatsLabel,
            Widgets.sectionTitle("Hardest Words"), hardestWordsArea,
            Widgets.sectionTitle("Portfolio Summary"), analyticsArea, buttons, folderButtons);
        charts.setId("statisticsCharts");
        charts.setPadding(new Insets(24));
        ScrollPane scrollPane = new ScrollPane(charts);
        scrollPane.setFitToWidth(true);
        tab = Widgets.tab("statisticsTab", "Statistics", scrollPane);
        tab.setOnSelectionChanged(event -> {
            if (tab.isSelected()) {
                visited = true;
                context.errors().guard("Refresh statistics failed", this::refresh);
            }
        });
        context.changes().subscribe(changes -> {
            if (visited && (changes.contains(DataChange.WORDS) || changes.contains(DataChange.REVIEWS))) {
                refresh();
            }
        });
        context.decks().onSwitch(deck -> {
            if (visited) {
                refresh();
            }
        });
    }

    public Tab tab() {
        return tab;
    }

    public void refresh() {
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
        analyticsArea.setText("Spaced repetition: intervals grow when recall is strong and shrink after weak recall."
            + System.lineSeparator() + "Retrieval practice: every review stores the typed answer and response time."
            + System.lineSeparator() + "Adaptive scheduling: low similarity increases lapse pressure and future urgency."
            + System.lineSeparator() + "Learning analytics: charts summarize volume, accuracy, memory strength and hard words.");
    }
}
