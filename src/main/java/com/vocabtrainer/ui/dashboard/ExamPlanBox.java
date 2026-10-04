package com.vocabtrainer.ui.dashboard;

import com.vocabtrainer.service.ExamCountdown;
import com.vocabtrainer.service.ExamPlanService;
import com.vocabtrainer.service.NewCardPlan;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.Formats;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Optional;
import java.util.OptionalInt;

import static com.vocabtrainer.util.Messages.tr;

/**
 * The Dashboard's exam box: the countdown to the current deck's exam ("GRE in 45 days"), "Set exam
 * date", and how many new words a day it takes to start every new word before the exam, with a
 * button that sets the deck's new-words-per-day limit to it.
 */
final class ExamPlanBox {
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
        Button editButton = new Button(tr("exam.edit"));
        editButton.setId("editExamButton");
        editButton.setOnAction(event -> context.errors().guard(tr("exam.notSaved"), this::editExam));
        planLabel.setId("newWordPlanLabel");
        planLabel.setWrapText(true);
        planLabel.setMinHeight(Region.USE_PREF_SIZE);
        applyPlanButton.setId("applyNewWordPlanButton");
        applyPlanButton.setOnAction(event -> context.errors().guard(tr("exam.plan.failed"), this::applyPlan));
        statusLabel.setId("examStatusLabel");
        statusLabel.setWrapText(true);
        statusLabel.setMinHeight(Region.USE_PREF_SIZE);
        statusLabel.getStyleClass().add("hint-text");
        root = new VBox(10, Widgets.sectionTitle(tr("exam.title")), countdownLabel, dateLabel, editButton, planLabel,
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
            countdownLabel.setText(tr("exam.none"));
            dateLabel.setText(tr("exam.none.hint"));
        } else {
            ExamCountdown exam = countdown.get();
            countdownLabel.setText(exam.toDisplayText());
            String scope = planService.settings().deckExam(deckId).isPresent() ? tr("exam.scope.deck")
                : tr("exam.scope.every");
            String date = Formats.weekdayDate(exam.exam().date()) + " · " + scope;
            dateLabel.setText(exam.isOver() ? tr("exam.over", date) : date);
        }
        Optional<NewCardPlan> plan = planService.newCardPlan(deckId);
        planLabel.setText(plan.map(NewCardPlan::toDisplayText).orElse(""));
        show(planLabel, plan.isPresent());
        boolean canApply = plan.isPresent() && !plan.get().isDone() && !plan.get().isOnTrack();
        show(applyPlanButton, canApply);
        if (canApply) {
            applyPlanButton.setText(tr("exam.plan.apply", plan.get().limitToApply()));
        }
    }

    private void editExam() {
        OptionalInt moved = ExamDialog.open(context, planService);
        if (moved.isEmpty()) {
            return;
        }
        int count = moved.getAsInt();
        statusLabel.setText(count == 0 ? tr("review.saved") : tr("exam.saved.moved", count));
    }

    private void applyPlan() {
        long deckId = context.decks().currentId();
        NewCardPlan plan = planService.applyNewCardPlan(deckId);
        statusLabel.setText(tr("exam.plan.applied", plan.limitToApply()));
        context.changes().publish(DataChange.REVIEW_SETTINGS);
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
