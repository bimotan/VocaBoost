package com.vocabtrainer.ui.stats;

import com.vocabtrainer.domain.Exam;
import com.vocabtrainer.domain.WorkloadDay;
import com.vocabtrainer.service.ExamPlanService;
import com.vocabtrainer.service.StatsService;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.StackedBarChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The Statistics tab's workload forecast: the reviews due and the new words planned on each of the
 * next 14 or 30 study days (see {@link StatsService#workloadForecast}), stacked, with the exam day
 * marked when it falls in the range.
 */
final class WorkloadForecastChart {
    static final String REVIEWS_SERIES = "Reviews due";
    static final String NEW_WORDS_SERIES = "New words";
    private static final List<Integer> RANGES = List.of(14, 30);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE M/d", Locale.ENGLISH);

    private final ViewContext context;
    private final StatsService statsService;
    private final ExamPlanService examPlanService;
    private final StackedBarChart<String, Number> chart;
    private final ComboBox<Integer> rangeSelector = new ComboBox<>(FXCollections.observableArrayList(RANGES));
    private final Label summaryLabel = new Label();
    private final VBox root;

    WorkloadForecastChart(ViewContext context, StatsService statsService, ExamPlanService examPlanService) {
        this.context = context;
        this.statsService = statsService;
        this.examPlanService = examPlanService;
        CategoryAxis dayAxis = new CategoryAxis();
        dayAxis.setTickLabelRotation(-35);
        NumberAxis countAxis = new NumberAxis();
        countAxis.setMinorTickVisible(false);
        chart = new StackedBarChart<>(dayAxis, countAxis);
        chart.setId("workloadForecastChart");
        chart.setAnimated(false);
        // Reviews blue and new words orange (app.css), which stay apart for colour-blind eyes too.
        chart.getStyleClass().add("workload-chart");
        chart.setCategoryGap(4);
        chart.setPrefHeight(300);
        chart.setMinHeight(300);

        rangeSelector.setId("forecastRangeSelector");
        rangeSelector.setAccessibleText("Forecast range");
        rangeSelector.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer days) {
                return days == null ? "" : "Next " + days + " days";
            }

            @Override
            public Integer fromString(String text) {
                return RANGES.get(0);
            }
        });
        rangeSelector.setValue(RANGES.get(0));
        rangeSelector.valueProperty().addListener((observable, oldDays, newDays) ->
            context.errors().guard("Refresh forecast failed", () -> refresh(context.decks().currentId())));
        summaryLabel.setId("forecastSummaryLabel");
        summaryLabel.setWrapText(true);
        HBox title = new HBox(12, Widgets.sectionTitle("Workload Forecast"), rangeSelector);
        title.setAlignment(Pos.CENTER_LEFT);
        root = new VBox(8, title, chart, summaryLabel);
    }

    Node root() {
        return root;
    }

    /** Shows the forecast of {@code deckId} for the chosen number of days. */
    void refresh(long deckId) {
        int days = rangeSelector.getValue() == null ? RANGES.get(0) : rangeSelector.getValue();
        List<WorkloadDay> forecast = statsService.workloadForecast(deckId, days);
        Optional<Exam> exam = examPlanService.examFor(deckId);
        List<String> categories = new ArrayList<>();
        XYChart.Series<String, Number> reviews = new XYChart.Series<>();
        reviews.setName(REVIEWS_SERIES);
        XYChart.Series<String, Number> newWords = new XYChart.Series<>();
        newWords.setName(NEW_WORDS_SERIES);
        for (int i = 0; i < forecast.size(); i++) {
            WorkloadDay day = forecast.get(i);
            String category = category(day.date(), i == 0, exam);
            categories.add(category);
            reviews.getData().add(new XYChart.Data<>(category, day.reviews()));
            newWords.getData().add(new XYChart.Data<>(category, day.newWords()));
        }
        ((CategoryAxis) chart.getXAxis()).setCategories(FXCollections.observableArrayList(categories));
        chart.getData().setAll(List.of(reviews, newWords));
        summaryLabel.setText(summary(forecast, days, exam));
    }

    /** "Today", "10/3", or "11/16 GRE" on the exam day. */
    private static String category(LocalDate date, boolean today, Optional<Exam> exam) {
        String label = today ? "Today" : date.getMonthValue() + "/" + date.getDayOfMonth();
        return exam.filter(found -> found.date().equals(date)).map(found -> label + " " + found.name()).orElse(label);
    }

    private static String summary(List<WorkloadDay> forecast, int days, Optional<Exam> exam) {
        int reviews = forecast.stream().mapToInt(WorkloadDay::reviews).sum();
        int newWords = forecast.stream().mapToInt(WorkloadDay::newWords).sum();
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT,
            "Next %d days: %,d %s due and %,d new %s planned.", days, reviews, reviews == 1 ? "review" : "reviews",
            newWords, newWords == 1 ? "word" : "words"));
        forecast.stream()
            .max(Comparator.comparingInt(day -> day.reviews() + day.newWords()))
            .filter(day -> day.reviews() + day.newWords() > 0)
            .ifPresent(day -> text.append(String.format(Locale.ROOT, " Busiest day: %s with %,d cards.",
                DAY.format(day.date()), day.reviews() + day.newWords())));
        LocalDate last = forecast.get(forecast.size() - 1).date();
        exam.filter(found -> !found.date().isBefore(forecast.get(0).date()) && !found.date().isAfter(last))
            .ifPresent(found -> text.append(" ").append(found.name()).append(" on ").append(DAY.format(found.date()))
                .append('.'));
        return text.toString();
    }
}
