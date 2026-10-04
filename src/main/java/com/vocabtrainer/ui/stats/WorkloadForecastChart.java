package com.vocabtrainer.ui.stats;

import com.vocabtrainer.domain.Exam;
import com.vocabtrainer.domain.WorkloadDay;
import com.vocabtrainer.service.ExamPlanService;
import com.vocabtrainer.service.StatsService;
import com.vocabtrainer.ui.Formats;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import com.vocabtrainer.util.Messages;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static com.vocabtrainer.util.Messages.tr;

/**
 * The Statistics tab's workload forecast: the reviews due and the new words planned on each of the
 * next 14 or 30 study days (see {@link StatsService#workloadForecast}), stacked, with the exam day
 * marked when it falls in the range.
 */
final class WorkloadForecastChart {
    private static final List<Integer> RANGES = List.of(14, 30);

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
        rangeSelector.setAccessibleText(tr("forecast.range.accessible"));
        rangeSelector.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer days) {
                return days == null ? "" : tr("forecast.range", days);
            }

            @Override
            public Integer fromString(String text) {
                return RANGES.get(0);
            }
        });
        rangeSelector.setValue(RANGES.get(0));
        rangeSelector.valueProperty().addListener((observable, oldDays, newDays) ->
            context.errors().guard(tr("forecast.refreshFailed"), () -> refresh(context.decks().currentId())));
        summaryLabel.setId("forecastSummaryLabel");
        summaryLabel.setWrapText(true);
        HBox title = new HBox(12, Widgets.sectionTitle(tr("forecast.title")), rangeSelector);
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
        reviews.setName(tr("forecast.series.reviews"));
        XYChart.Series<String, Number> newWords = new XYChart.Series<>();
        newWords.setName(tr("forecast.series.newWords"));
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
        String label = today ? tr("forecast.today") : Formats.shortDate(date);
        return exam.filter(found -> found.date().equals(date)).map(found -> label + " " + found.name()).orElse(label);
    }

    private static String summary(List<WorkloadDay> forecast, int days, Optional<Exam> exam) {
        int reviews = forecast.stream().mapToInt(WorkloadDay::reviews).sum();
        int newWords = forecast.stream().mapToInt(WorkloadDay::newWords).sum();
        List<String> text = new ArrayList<>();
        text.add(tr("forecast.summary", days, reviews, newWords));
        forecast.stream()
            .max(Comparator.comparingInt(day -> day.reviews() + day.newWords()))
            .filter(day -> day.reviews() + day.newWords() > 0)
            .ifPresent(day -> text.add(tr("forecast.busiest", Formats.weekdayShortDate(day.date()),
                day.reviews() + day.newWords())));
        LocalDate last = forecast.get(forecast.size() - 1).date();
        exam.filter(found -> !found.date().isBefore(forecast.get(0).date()) && !found.date().isAfter(last))
            .ifPresent(found -> text.add(tr("forecast.exam", found.name(), Formats.weekdayShortDate(found.date()))));
        return Messages.sentences(text);
    }
}
