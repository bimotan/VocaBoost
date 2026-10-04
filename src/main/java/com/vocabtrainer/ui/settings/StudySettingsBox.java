package com.vocabtrainer.ui.settings;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.GoalTargets;
import com.vocabtrainer.service.ExamCountdown;
import com.vocabtrainer.service.ExamPlanService;
import com.vocabtrainer.service.GoalSettings;
import com.vocabtrainer.service.NewCardPlan;
import com.vocabtrainer.service.ReviewSettings;
import com.vocabtrainer.service.SchedulingSettings;
import com.vocabtrainer.service.scheduling.Fsrs;
import com.vocabtrainer.service.scheduling.SchedulingOptions;
import com.vocabtrainer.service.scheduling.StudyDay;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.Formats;
import com.vocabtrainer.ui.LazyRefresh;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import com.vocabtrainer.ui.dashboard.ExamDialog;
import com.vocabtrainer.ui.dashboard.GoalsDialog;
import com.vocabtrainer.util.DateTimeUtil;
import com.vocabtrainer.util.Messages;
import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tab;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.IntStream;

import static com.vocabtrainer.util.Messages.tr;

/**
 * How the user studies: the desired retention and the hour a study day starts (the scheduler), the
 * new-words-per-day limit of every deck, the daily goals (through the Dashboard's "Edit goals"
 * form) and the exam date with its new-word plan (through the Dashboard's "Set exam date" form). A change is saved and applied at once: the next rating, interval preview and due count use
 * it, and {@link DataChange#REVIEW_SETTINGS} tells the views that what is due today may have changed.
 */
final class StudySettingsBox {
    /** The lowest retention the slider offers: below it most words would be forgotten before they come back. */
    private static final double MIN_OFFERED_RETENTION = 0.80;
    /** The stability the retention hint compares intervals for: 10 days, the interval it gives at 90%. */
    private static final double HINT_STABILITY_DAYS = 10;

    private final ViewContext context;
    private final SchedulingSettings scheduling;
    private final ReviewSettings reviewSettings;
    private final GoalSettings goalSettings;
    private final ExamPlanService examPlans;
    private final Slider retentionSlider = new Slider();
    private final Label retentionLabel = new Label();
    private final Label retentionHint = hint("desiredRetentionHintLabel");
    private final ComboBox<Integer> rolloverSelector = new ComboBox<>();
    private final Spinner<Integer> newCardsSpinner = new Spinner<>();
    private final Label newCardsHint = hint("newCardsPerDayHintLabel");
    /** Shown while the current deck has a limit of its own: drops it, so the deck follows the default. */
    private final Button useDefaultNewCardsButton = new Button(tr("settings.newWords.useDefault"));
    private final Label goalsSummary = hint("goalsSummaryLabel");
    private final Label examSummary = hint("examSummaryLabel");
    private final VBox root;
    /** Set while the controls are made to show the saved settings, so those are not saved again. */
    private boolean showingSaved;
    /** The saved default new-words limit, which the spinner goes back to when a change cannot be saved. */
    private int savedNewCardsPerDay;
    /** Reads the current deck's settings again when the Settings tab is shown; null until {@link #refreshWhenShown}. */
    private LazyRefresh deckRefresh;

    StudySettingsBox(ViewContext context, SchedulingSettings scheduling, ReviewSettings reviewSettings,
                     GoalSettings goalSettings, ExamPlanService examPlans) {
        this.context = context;
        this.scheduling = scheduling;
        this.reviewSettings = reviewSettings;
        this.goalSettings = goalSettings;
        this.examPlans = examPlans;

        configureRetention();
        configureRollover();
        configureNewCardsPerDay();
        Button editGoalsButton = new Button(tr("goals.edit"));
        editGoalsButton.setId("settingsEditGoalsButton");
        editGoalsButton.setOnAction(event -> GoalsDialog.open(context, goalSettings));
        Button editExamButton = new Button(tr("exam.edit"));
        editExamButton.setId("settingsEditExamButton");
        editExamButton.setOnAction(event ->
            context.errors().guard(tr("exam.notSaved"), () -> ExamDialog.open(context, examPlans)));

        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(6);
        HBox retentionRow = new HBox(10, retentionSlider, retentionLabel);
        retentionRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(retentionSlider, Priority.ALWAYS);
        addRow(form, 0, Widgets.formLabel(tr("settings.retention"), retentionSlider), retentionRow, retentionHint);
        addRow(form, 2, Widgets.formLabel(tr("settings.rollover"), rolloverSelector), rolloverSelector,
            hint("dayRolloverHintLabel", tr("settings.rollover.hint")));
        HBox newCardsRow = new HBox(10, newCardsSpinner, useDefaultNewCardsButton);
        newCardsRow.setAlignment(Pos.CENTER_LEFT);
        addRow(form, 4, Widgets.formLabel(tr("settings.newWords"), newCardsSpinner), newCardsRow, newCardsHint);
        addRow(form, 6, new Label(tr("dashboard.goals.title")), editGoalsButton, goalsSummary);
        addRow(form, 8, new Label(tr("exam.title")), editExamButton, examSummary);
        GridPane.setHgrow(retentionRow, Priority.ALWAYS);

        showSaved();
        root = new VBox(10, Widgets.sectionTitle(tr("settings.study.title")), form);
        context.decks().onSwitch(deck -> refreshDeckSettings());
        // The exam row's new-word plan counts the deck's new words and today's first reviews.
        context.changes().subscribe(changes -> {
            if (changes.contains(DataChange.GOALS) || changes.contains(DataChange.REVIEW_SETTINGS)
                || changes.contains(DataChange.DECKS) || changes.contains(DataChange.WORDS)
                || changes.contains(DataChange.REVIEWS)) {
                refreshDeckSettings();
            }
        });
    }

    Node root() {
        return root;
    }

    /**
     * Reads the current deck's settings only while {@code tab} is shown, and at every visit, since the
     * exam countdown depends on the clock: a rating or an import on another tab marks them stale.
     */
    void refreshWhenShown(Tab tab) {
        deckRefresh = new LazyRefresh(tab, this::showDeckSettings, context.errors(), tr("settings.refreshFailed"),
            true);
    }

    /**
     * What a desired retention means for the user: the trade-off, and the interval it gives where 90%
     * gives 10 days.
     */
    static String retentionHint(double retention) {
        String tradeOff = tr("settings.retention.tradeOff");
        String applies = tr("settings.retention.applies");
        if (Math.round(retention * 100) == Math.round(Fsrs.DEFAULT_DESIRED_RETENTION * 100)) {
            return Messages.sentences(List.of(tradeOff, tr("settings.retention.default"), applies));
        }
        long days = Math.max(1L, Math.round(new Fsrs(retention, Fsrs.DEFAULT_MAXIMUM_INTERVAL)
            .rawInterval(HINT_STABILITY_DAYS)));
        return Messages.sentences(List.of(tradeOff, tr("settings.retention.example", Formats.percent(retention),
            DateTimeUtil.days(Math.round(HINT_STABILITY_DAYS)), DateTimeUtil.days(days)), applies));
    }

    private void configureRetention() {
        retentionSlider.setId("desiredRetentionSlider");
        retentionSlider.setMax(SchedulingOptions.MAX_DESIRED_RETENTION);
        retentionSlider.setMin(MIN_OFFERED_RETENTION);
        retentionSlider.setMajorTickUnit(0.05);
        retentionSlider.setMinorTickCount(4);
        retentionSlider.setBlockIncrement(0.01);
        retentionSlider.setSnapToTicks(true);
        retentionSlider.setShowTickMarks(true);
        retentionSlider.setShowTickLabels(true);
        retentionSlider.setLabelFormatter(new StringConverter<>() {
            @Override
            public String toString(Double value) {
                return value == null ? "" : Formats.percent(value);
            }

            @Override
            public Double fromString(String text) {
                return null;
            }
        });
        retentionSlider.setMaxWidth(420);
        retentionLabel.setId("desiredRetentionLabel");
        retentionLabel.setMinWidth(Region.USE_PREF_SIZE);
        retentionLabel.getStyleClass().add("strong-text");
        // A drag is saved when the thumb is let go; the keys and a click save at once.
        retentionSlider.valueProperty().addListener((observable, oldValue, value) -> {
            showRetention(value.doubleValue());
            if (!showingSaved && !retentionSlider.isValueChanging()) {
                saveRetention();
            }
        });
        retentionSlider.valueChangingProperty().addListener((observable, wasChanging, changing) -> {
            if (!showingSaved && !changing) {
                saveRetention();
            }
        });
    }

    private void configureRollover() {
        rolloverSelector.setId("dayRolloverHourSelector");
        IntStream.rangeClosed(0, 23).forEach(rolloverSelector.getItems()::add);
        rolloverSelector.setCellFactory(list -> hourCell());
        rolloverSelector.setButtonCell(hourCell());
        rolloverSelector.setVisibleRowCount(12);
        rolloverSelector.valueProperty().addListener((observable, oldHour, hour) -> {
            if (!showingSaved && hour != null) {
                saveRollover(hour);
            }
        });
    }

    private void configureNewCardsPerDay() {
        newCardsSpinner.setId("defaultNewCardsPerDaySpinner");
        SpinnerValueFactory.IntegerSpinnerValueFactory factory = new SpinnerValueFactory.IntegerSpinnerValueFactory(
            0, ReviewSettings.MAX_NEW_CARDS_PER_DAY, ReviewSettings.DEFAULT_NEW_CARDS_PER_DAY, 5);
        // Text that is not a number (an emptied field) keeps the value instead of clearing it.
        factory.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer value) {
                return value == null ? "" : value.toString();
            }

            @Override
            public Integer fromString(String text) {
                try {
                    return Integer.valueOf(text.trim());
                } catch (NumberFormatException e) {
                    return factory.getValue();
                }
            }
        });
        newCardsSpinner.setValueFactory(factory);
        newCardsSpinner.setEditable(true);
        newCardsSpinner.setPrefWidth(90);
        newCardsSpinner.getEditor().setTextFormatter(new TextFormatter<String>(change ->
            change.getControlNewText().matches("\\d{0,4}") ? change : null));
        // A typed value counts after Enter and when the field loses the focus.
        newCardsSpinner.getEditor().addEventHandler(ActionEvent.ACTION, event -> commitEditorText());
        newCardsSpinner.focusedProperty().addListener((observable, wasFocused, focused) -> {
            if (!focused) {
                commitEditorText();
            }
        });
        newCardsSpinner.valueProperty().addListener((observable, oldValue, value) -> {
            if (!showingSaved && value != null) {
                saveNewCardsPerDay(value);
            }
        });
        useDefaultNewCardsButton.setId("useDefaultNewCardsPerDayButton");
        useDefaultNewCardsButton.setOnAction(event -> {
            long deckId = context.decks().currentId();
            apply(tr("settings.newWords.failed"), () -> reviewSettings.clearNewCardsPerDay(deckId));
        });
    }

    private void commitEditorText() {
        SpinnerValueFactory<Integer> factory = newCardsSpinner.getValueFactory();
        factory.setValue(factory.getConverter().fromString(newCardsSpinner.getEditor().getText()));
        newCardsSpinner.getEditor().setText(factory.getConverter().toString(factory.getValue()));
    }

    /** The slider's value as it is saved: whole percent. */
    private double chosenRetention() {
        return Math.round(retentionSlider.getValue() * 100) / 100.0;
    }

    private void saveRetention() {
        double retention = chosenRetention();
        if (retention == scheduling.options().desiredRetention()) {
            return;
        }
        apply(tr("settings.retention.failed"), () -> scheduling.saveDesiredRetention(retention));
    }

    private void saveRollover(int hour) {
        if (hour == scheduling.options().dayRolloverHour()) {
            return;
        }
        apply(tr("settings.rollover.failed"), () -> scheduling.saveDayRolloverHour(hour));
    }

    private void saveNewCardsPerDay(int limit) {
        if (limit == savedNewCardsPerDay) {
            return;
        }
        apply(tr("settings.newWords.failed"), () -> {
            reviewSettings.saveDefaultNewCardsPerDay(limit);
            savedNewCardsPerDay = limit;
        });
    }

    /**
     * Saves a setting and tells the views; if it cannot be saved, the failure is reported and the
     * controls go back to the settings in effect.
     */
    private void apply(String errorTitle, Runnable save) {
        try {
            save.run();
        } catch (RuntimeException e) {
            context.errors().reportFailure(errorTitle, e);
            showControls();
            return;
        }
        context.errors().guard(tr("settings.refreshViewsFailed"),
            () -> context.changes().publish(DataChange.REVIEW_SETTINGS));
    }

    /** Reads the saved settings and shows them. */
    private void showSaved() {
        savedNewCardsPerDay = reviewSettings.defaultNewCardsPerDay();
        showControls();
        showDeckSettings();
    }

    /** Shows the settings in effect in the controls: the scheduler's options and the saved default limit. */
    private void showControls() {
        SchedulingOptions options = scheduling.options();
        showingSaved = true;
        try {
            // A retention below the slider's range (set by an older version or by hand) is shown as it is.
            retentionSlider.setMin(Math.min(MIN_OFFERED_RETENTION, options.desiredRetention()));
            retentionSlider.setValue(options.desiredRetention());
            showRetention(options.desiredRetention());
            rolloverSelector.setValue(options.dayRolloverHour());
            newCardsSpinner.getValueFactory().setValue(savedNewCardsPerDay);
        } finally {
            showingSaved = false;
        }
    }

    private void showRetention(double retention) {
        double shown = Math.round(retention * 100) / 100.0;
        retentionLabel.setText(Formats.percent(shown));
        retentionHint.setText(retentionHint(shown));
    }

    private void refreshDeckSettings() {
        context.errors().guard(tr("settings.refreshFailed"),
            deckRefresh == null ? this::showDeckSettings : deckRefresh::markStale);
    }

    /** What the current deck does: its own new-words limit and goals, if it has them. */
    private void showDeckSettings() {
        Deck deck = context.decks().current();
        long deckId = deck.getId();
        List<String> newCards = new ArrayList<>(List.of(tr("settings.newWords.hint")));
        boolean ownLimit = reviewSettings.hasOwnNewCardsPerDay(deckId);
        if (ownLimit) {
            newCards.add(tr("settings.newWords.deckOwn", deck.getName(), reviewSettings.newCardsPerDay(deckId)));
        }
        newCardsHint.setText(Messages.sentences(newCards));
        useDefaultNewCardsButton.setVisible(ownLimit);
        useDefaultNewCardsButton.setManaged(ownLimit);

        GoalTargets defaults = goalSettings.defaults();
        int session = goalSettings.sessionGoal();
        List<String> goals = new ArrayList<>(List.of(tr("settings.goals.every", goalText(defaults),
            session == 0 ? tr("settings.goals.sessionAll") : tr("settings.goals.sessionCards", session))));
        goalSettings.deckGoals(deckId)
            .ifPresent(own -> goals.add(tr("settings.goals.deckOwn", deck.getName(), goalText(own))));
        goalsSummary.setText(Messages.sentences(goals));
        examSummary.setText(examText(deck.getName(), examPlans.countdown(deckId),
            examPlans.settings().deckExam(deckId).isPresent(), examPlans.newCardPlan(deckId)));
    }

    /**
     * The current deck's exam, whose it is, and the new words a day it takes to start every new word
     * before it; or what an exam date does.
     */
    static String examText(String deckName, Optional<ExamCountdown> countdown, boolean ownExam,
                           Optional<NewCardPlan> plan) {
        if (countdown.isEmpty()) {
            return tr("settings.exam.none");
        }
        ExamCountdown exam = countdown.get();
        String text = tr("settings.exam.text", exam.toDisplayText(), Formats.weekdayDate(exam.exam().date()),
            ownExam ? tr("settings.exam.own", deckName) : tr("settings.exam.every"));
        return plan.map(found -> Messages.sentences(List.of(text, found.toDisplayText()))).orElse(text);
    }

    private static String goalText(GoalTargets goals) {
        return tr("settings.goals.text", goals.reviewGoal(), goals.newWordGoal());
    }

    private static void addRow(GridPane form, int row, Label label, Node control, Label hint) {
        label.setMinWidth(Region.USE_PREF_SIZE);
        form.add(label, 0, row);
        form.add(control, 1, row);
        form.add(hint, 1, row + 1);
        GridPane.setHgrow(hint, Priority.ALWAYS);
    }

    private static Label hint(String id) {
        return hint(id, "");
    }

    private static Label hint(String id, String text) {
        Label label = Widgets.hint(text);
        label.setId(id);
        return label;
    }

    private static ListCell<Integer> hourCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Integer hour, boolean empty) {
                super.updateItem(hour, empty);
                setText(empty || hour == null ? null : hourText(hour));
            }
        };
    }

    /** "04:00", with "(default)" after the default hour. */
    static String hourText(int hour) {
        String time = String.format(Locale.ROOT, "%02d:00", hour);
        return hour == StudyDay.DEFAULT_ROLLOVER_HOUR ? tr("settings.rollover.default", time) : time;
    }
}
