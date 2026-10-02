package com.vocabtrainer.ui.settings;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.GoalTargets;
import com.vocabtrainer.service.GoalSettings;
import com.vocabtrainer.service.ReviewSettings;
import com.vocabtrainer.service.SchedulingSettings;
import com.vocabtrainer.service.scheduling.Fsrs;
import com.vocabtrainer.service.scheduling.SchedulingOptions;
import com.vocabtrainer.service.scheduling.StudyDay;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.Formats;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import com.vocabtrainer.ui.dashboard.GoalsDialog;
import com.vocabtrainer.util.DateTimeUtil;
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
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.Locale;
import java.util.stream.IntStream;

/**
 * How the user studies: the desired retention and the hour a study day starts (the scheduler), the
 * new-words-per-day limit of every deck, and the daily goals (through the Dashboard's "Edit goals"
 * form). A change is saved and applied at once: the next rating, interval preview and due count use
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
    private final Slider retentionSlider = new Slider();
    private final Label retentionLabel = new Label();
    private final Label retentionHint = hint("desiredRetentionHintLabel");
    private final ComboBox<Integer> rolloverSelector = new ComboBox<>();
    private final Spinner<Integer> newCardsSpinner = new Spinner<>();
    private final Label newCardsHint = hint("newCardsPerDayHintLabel");
    /** Shown while the current deck has a limit of its own: drops it, so the deck follows the default. */
    private final Button useDefaultNewCardsButton = new Button("Use the default for this deck");
    private final Label goalsSummary = hint("goalsSummaryLabel");
    private final VBox root;
    /** Set while the controls are made to show the saved settings, so those are not saved again. */
    private boolean showingSaved;
    /** The saved default new-words limit, which the spinner goes back to when a change cannot be saved. */
    private int savedNewCardsPerDay;

    StudySettingsBox(ViewContext context, SchedulingSettings scheduling, ReviewSettings reviewSettings,
                     GoalSettings goalSettings) {
        this.context = context;
        this.scheduling = scheduling;
        this.reviewSettings = reviewSettings;
        this.goalSettings = goalSettings;

        configureRetention();
        configureRollover();
        configureNewCardsPerDay();
        Button editGoalsButton = new Button("Edit goals");
        editGoalsButton.setId("settingsEditGoalsButton");
        editGoalsButton.setOnAction(event -> GoalsDialog.open(context, goalSettings));

        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(6);
        HBox retentionRow = new HBox(10, retentionSlider, retentionLabel);
        retentionRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(retentionSlider, Priority.ALWAYS);
        addRow(form, 0, Widgets.formLabel("Desired _retention", retentionSlider), retentionRow, retentionHint);
        addRow(form, 2, Widgets.formLabel("New study day _starts at", rolloverSelector), rolloverSelector,
            hint("dayRolloverHintLabel", "Reviews before this hour count for the day before. What is due today"
                + " and the daily goals follow the study day."));
        HBox newCardsRow = new HBox(10, newCardsSpinner, useDefaultNewCardsButton);
        newCardsRow.setAlignment(Pos.CENTER_LEFT);
        addRow(form, 4, Widgets.formLabel("New _words per day", newCardsSpinner), newCardsRow, newCardsHint);
        addRow(form, 6, new Label("Daily goals"), editGoalsButton, goalsSummary);
        GridPane.setHgrow(retentionRow, Priority.ALWAYS);

        showSaved();
        root = new VBox(10, Widgets.sectionTitle("Study"), form);
        context.decks().onSwitch(deck -> refreshDeckSettings());
        context.changes().subscribe(changes -> {
            if (changes.contains(DataChange.GOALS) || changes.contains(DataChange.REVIEW_SETTINGS)
                || changes.contains(DataChange.DECKS)) {
                refreshDeckSettings();
            }
        });
    }

    Node root() {
        return root;
    }

    /**
     * What a desired retention means for the user: the trade-off, and the interval it gives where 90%
     * gives 10 days.
     */
    static String retentionHint(double retention) {
        String tradeOff = "Higher = more reviews: you forget fewer words, but each one comes back sooner.";
        if (Math.round(retention * 100) == Math.round(Fsrs.DEFAULT_DESIRED_RETENTION * 100)) {
            return tradeOff + " 90% is the default. A change applies from the next rating; due dates already set stay.";
        }
        long days = Math.max(1L, Math.round(new Fsrs(retention, Fsrs.DEFAULT_MAXIMUM_INTERVAL)
            .rawInterval(HINT_STABILITY_DAYS)));
        return tradeOff + " At " + Formats.percent(retention) + ", a word that 90% brings back after "
            + DateTimeUtil.days(Math.round(HINT_STABILITY_DAYS)) + " comes back after " + DateTimeUtil.days(days)
            + ". A change applies from the next rating; due dates already set stay.";
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
            apply("New words per day not saved", () -> reviewSettings.clearNewCardsPerDay(deckId));
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
        apply("Desired retention not saved", () -> scheduling.saveDesiredRetention(retention));
    }

    private void saveRollover(int hour) {
        if (hour == scheduling.options().dayRolloverHour()) {
            return;
        }
        apply("Day rollover not saved", () -> scheduling.saveDayRolloverHour(hour));
    }

    private void saveNewCardsPerDay(int limit) {
        if (limit == savedNewCardsPerDay) {
            return;
        }
        apply("New words per day not saved", () -> {
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
        context.errors().guard("Setting saved, but refreshing the views failed",
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
        context.errors().guard("Refreshing the settings failed", this::showDeckSettings);
    }

    /** What the current deck does: its own new-words limit and goals, if it has them. */
    private void showDeckSettings() {
        Deck deck = context.decks().current();
        long deckId = deck.getId();
        String newCards = "The most new words a deck introduces per study day, unless the Review tab set a"
            + " limit for that deck.";
        boolean ownLimit = reviewSettings.hasOwnNewCardsPerDay(deckId);
        if (ownLimit) {
            newCards += " " + deck.getName() + " has its own limit: " + reviewSettings.newCardsPerDay(deckId) + ".";
        }
        newCardsHint.setText(newCards);
        useDefaultNewCardsButton.setVisible(ownLimit);
        useDefaultNewCardsButton.setManaged(ownLimit);

        GoalTargets defaults = goalSettings.defaults();
        int session = goalSettings.sessionGoal();
        String goals = "Every deck: " + goalText(defaults) + "; review sessions of "
            + (session == 0 ? "all due cards" : session + " cards") + ".";
        goals += goalSettings.deckGoals(deckId)
            .map(own -> " " + deck.getName() + " has its own goals: " + goalText(own) + ".")
            .orElse("");
        goalsSummary.setText(goals);
    }

    private static String goalText(GoalTargets goals) {
        return goals.reviewGoal() + " reviews and " + goals.newWordGoal() + " new words per day";
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
        return String.format(Locale.ROOT, "%02d:00", hour)
            + (hour == StudyDay.DEFAULT_ROLLOVER_HOUR ? " (default)" : "");
    }
}
