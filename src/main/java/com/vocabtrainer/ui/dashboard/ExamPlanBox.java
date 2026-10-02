package com.vocabtrainer.ui.dashboard;

import com.vocabtrainer.service.ExamCountdown;
import com.vocabtrainer.service.ExamPlanService;
import com.vocabtrainer.service.NewCardPlan;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The Dashboard's exam box: the countdown to the current deck's exam ("GRE in 45 days"), "Set exam
 * date", and how many new words a day it takes to start every new word before the exam, with a
 * button that sets the deck's new-words-per-day limit to it.
 */
final class ExamPlanBox {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd", Locale.ENGLISH);

    private final ViewContext context;
    private final ExamPlanService planService;
    private final Label countdownLabel = new Label();
    private final Label dateLabel = new Label();
    private final Label planLabel = new Label();
    private final Button applyPlanButton = new Button();
    private final Label statusLabel = new Label();
    private final VBox root;

    ExamPlanBox(ViewContext context, ExamPlanService planService) {
        this.context = context;
        this.planService = planService;
        countdownLabel.setId("examCountdownLabel");
        countdownLabel.getStyleClass().add("exam-countdown");
        countdownLabel.setWrapText(true);
        dateLabel.setId("examDateLabel");
        dateLabel.getStyleClass().add("muted-text");
        dateLabel.setWrapText(true);
        Button editButton = new Button("Set exam date");
        editButton.setId("editExamButton");
        editButton.setOnAction(event -> context.errors().guard("Exam date not saved", this::editExam));
        planLabel.setId("newWordPlanLabel");
        planLabel.setWrapText(true);
        planLabel.setMinHeight(Region.USE_PREF_SIZE);
        applyPlanButton.setId("applyNewWordPlanButton");
        applyPlanButton.setOnAction(event -> context.errors().guard("New words per day not changed", this::applyPlan));
        statusLabel.setId("examStatusLabel");
        statusLabel.setWrapText(true);
        statusLabel.setMinHeight(Region.USE_PREF_SIZE);
        statusLabel.getStyleClass().add("hint-text");
        root = new VBox(10, Widgets.sectionTitle("Exam"), countdownLabel, dateLabel, editButton, planLabel,
            applyPlanButton, statusLabel);
        root.setId("examPlanBox");
        root.setMaxWidth(360);
        root.setPrefWidth(360);
        context.decks().onSwitch(deck -> statusLabel.setText(""));
    }

    Node root() {
        return root;
    }

    /** Shows the current deck's exam and plan. */
    void refresh(long deckId) {
        Optional<ExamCountdown> countdown = planService.countdown(deckId);
        if (countdown.isEmpty()) {
            countdownLabel.setText("No exam date");
            dateLabel.setText("Set the date of your exam to see a countdown, plan your new words and keep reviews"
                + " before it.");
        } else {
            ExamCountdown exam = countdown.get();
            countdownLabel.setText(exam.toDisplayText());
            String scope = planService.settings().deckExam(deckId).isPresent() ? "this deck's exam" : "every deck's exam";
            dateLabel.setText(DATE.format(exam.exam().date()) + " · " + scope
                + (exam.isOver() ? ". Set the next date or clear it." : ""));
        }
        Optional<NewCardPlan> plan = planService.newCardPlan(deckId);
        planLabel.setText(plan.map(NewCardPlan::toDisplayText).orElse(""));
        show(planLabel, plan.isPresent());
        boolean canApply = plan.isPresent() && !plan.get().isDone() && !plan.get().isOnTrack();
        show(applyPlanButton, canApply);
        if (canApply) {
            applyPlanButton.setText("Use " + plan.get().limitToApply() + " new words/day");
        }
    }

    private void editExam() {
        OptionalInt moved = ExamDialog.open(context, planService);
        if (moved.isEmpty()) {
            return;
        }
        int count = moved.getAsInt();
        statusLabel.setText(count == 0 ? "Saved."
            : count == 1 ? "Saved. 1 review due on or after the exam now comes before it."
            : "Saved. " + count + " reviews due on or after the exam now come before it.");
    }

    private void applyPlan() {
        long deckId = context.decks().currentId();
        NewCardPlan plan = planService.applyNewCardPlan(deckId);
        statusLabel.setText("New words per day set to " + plan.limitToApply() + ".");
        context.changes().publish(DataChange.REVIEW_SETTINGS);
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
