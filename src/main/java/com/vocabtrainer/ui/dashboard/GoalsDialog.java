package com.vocabtrainer.ui.dashboard;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.GoalTargets;
import com.vocabtrainer.service.GoalSettings;
import com.vocabtrainer.service.ReviewSettings;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;

import static com.vocabtrainer.util.Messages.tr;

/**
 * Edits the goals in a modal form: the daily review and new-word goals, either the defaults of
 * every deck or the current deck's own, and the session goal, which is the review session size of
 * every deck (the Review tab's "Session size"). The Dashboard and the Settings tab open it.
 */
public final class GoalsDialog {
    private final ViewContext context;
    private final GoalSettings settings;

    private GoalsDialog(ViewContext context, GoalSettings settings) {
        this.context = context;
        this.settings = settings;
    }

    /**
     * Shows the form for the current deck; once the goals are saved, publishes {@link DataChange#GOALS}.
     * Failures, also reading the goals for the form, are reported.
     */
    public static void open(ViewContext context, GoalSettings settings) {
        context.errors().guard(tr("goals.notSaved"), () -> {
            if (new GoalsDialog(context, settings).edit(context.decks().current())) {
                context.errors().guard(tr("goals.refreshFailed"),
                    () -> context.changes().publish(DataChange.GOALS));
            }
        });
    }

    /** Shows the form for {@code deck} and saves it on OK; true when the goals were saved. */
    private boolean edit(Deck deck) {
        GoalTargets defaults = settings.defaults();
        boolean ownGoals = settings.deckGoals(deck.getId()).isPresent();
        GoalTargets shown = settings.goalsFor(deck.getId());

        ToggleGroup scope = new ToggleGroup();
        RadioButton everyDeck = new RadioButton(tr("goals.form.everyDeck"));
        everyDeck.setId("goalScopeEveryDeck");
        everyDeck.setToggleGroup(scope);
        RadioButton thisDeck = new RadioButton(tr("form.onlyDeck", deck.getName()));
        thisDeck.setId("goalScopeThisDeck");
        thisDeck.setToggleGroup(scope);
        (ownGoals ? thisDeck : everyDeck).setSelected(true);

        Spinner<Integer> reviewGoal = spinner("reviewGoalSpinner", GoalTargets.MAX_GOAL, shown.reviewGoal(), 5);
        Spinner<Integer> newWordGoal = spinner("newWordGoalSpinner", GoalTargets.MAX_GOAL, shown.newWordGoal(), 1);
        Spinner<Integer> sessionGoal = spinner("sessionGoalSpinner", ReviewSettings.MAX_SESSION_SIZE,
            settings.sessionGoal(), 5);
        // Switching between the defaults and the deck's own goals shows the goals of that choice.
        scope.selectedToggleProperty().addListener((observable, oldToggle, newToggle) -> {
            GoalTargets goals = newToggle == thisDeck ? settings.goalsFor(deck.getId()) : defaults;
            reviewGoal.getValueFactory().setValue(goals.reviewGoal());
            newWordGoal.getValueFactory().setValue(goals.newWordGoal());
        });

        Label newWordsHint = hint(tr("goals.form.newWordsHint", deck.getName(), settings.newCardsPerDay(deck.getId())));
        Label sessionHint = hint(tr("goals.form.sessionHint"));

        GridPane form = new GridPane();
        form.setId("goalsForm");
        form.setHgap(10);
        form.setVgap(10);
        form.add(Widgets.formLabel(tr("goals.form.for"), everyDeck), 0, 0);
        form.add(new HBox(16, everyDeck, thisDeck), 1, 0);
        form.add(Widgets.formLabel(tr("goals.form.reviews"), reviewGoal), 0, 1);
        form.add(reviewGoal, 1, 1);
        form.add(Widgets.formLabel(tr("goals.form.newWords"), newWordGoal), 0, 2);
        form.add(newWordGoal, 1, 2);
        form.add(newWordsHint, 1, 3);
        form.add(Widgets.formLabel(tr("goals.form.session"), sessionGoal), 0, 4);
        form.add(sessionGoal, 1, 4);
        form.add(sessionHint, 1, 5);

        if (!context.dialogs().showForm(tr("goals.edit"), form)) {
            return false;
        }
        try {
            GoalTargets goals = new GoalTargets(value(reviewGoal, tr("goals.name.reviews")),
                value(newWordGoal, tr("goals.name.newWords")));
            int session = value(sessionGoal, tr("goals.name.session"));
            if (session > ReviewSettings.MAX_SESSION_SIZE) {
                throw new IllegalArgumentException(tr("goals.error.sessionRange", ReviewSettings.MAX_SESSION_SIZE));
            }
            if (thisDeck.isSelected()) {
                settings.saveDeckGoals(deck.getId(), goals);
            } else {
                settings.saveDefaults(goals);
                settings.clearDeckGoals(deck.getId());
            }
            settings.saveSessionGoal(session);
            return true;
        } catch (IllegalArgumentException e) {
            context.errors().showError(tr("goals.notSaved"), e.getMessage());
            return false;
        } catch (RuntimeException e) {
            context.errors().reportFailure(tr("goals.notSaved"), e);
            return false;
        }
    }

    /** An editable spinner from 0 to {@code max} that only takes digits. */
    private static Spinner<Integer> spinner(String id, int max, int value, int step) {
        Spinner<Integer> spinner = new Spinner<>();
        spinner.setId(id);
        spinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, max, Math.min(max, value), step));
        spinner.setEditable(true);
        spinner.setPrefWidth(100);
        spinner.getEditor().setTextFormatter(new TextFormatter<String>(change ->
            change.getControlNewText().matches("\\d{0,4}") ? change : null));
        return spinner;
    }

    /** The number typed into {@code spinner}, which OK takes even if it was not committed yet. */
    private static int value(Spinner<Integer> spinner, String name) {
        String text = spinner.getEditor().getText().trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(tr("goals.error.wholeNumber", name));
        }
        return Integer.parseInt(text);
    }

    private static Label hint(String text) {
        Label label = Widgets.hint(text);
        // A fixed width lets the grid give the wrapped lines their height.
        label.setPrefWidth(380);
        return label;
    }
}
