package com.vocabtrainer.ui.review;

import com.vocabtrainer.domain.DailyGoalProgress;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewOutcome;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.ReviewSessionSummary;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.service.AiService;
import com.vocabtrainer.service.GoalService;
import com.vocabtrainer.service.ReviewAnswer;
import com.vocabtrainer.service.ReviewService;
import com.vocabtrainer.ui.ConfiguredServices;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.Formats;
import com.vocabtrainer.ui.UiErrors;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tab;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Locale;
import java.util.Optional;

/** The Review tab: one card at a time, typed answer, self-rating and the session summary. */
public final class ReviewView {
    private final ViewContext context;
    private final ReviewService reviewService;
    private final GoalService goalService;
    private final ConfiguredServices configured;

    private Label reviewWordLabel;
    private Label reviewMetaLabel;
    private TextField answerField;
    private TextArea reviewResultArea;
    private VBox completionCard;
    private Label completionTitleLabel;
    private Label completionMetricsLabel;
    private Button submitAnswerButton;
    private HBox ratingButtons;
    private WordCard currentReviewWord;
    private ComboBox<ReviewMode> reviewModeSelector;
    private ComboBox<String> sessionSizeSelector;
    private TextField customSessionSizeField;
    private Label sessionProgressLabel;
    private final Tab tab;

    public ReviewView(ViewContext context, ReviewService reviewService, GoalService goalService,
                      ConfiguredServices configured) {
        this.context = context;
        this.reviewService = reviewService;
        this.goalService = goalService;
        this.configured = configured;
        this.tab = Widgets.tab("reviewTab", "Review", createContent());
        context.decks().onSwitch(deck -> {
            currentReviewWord = null;
            reviewService.resetSession(deck.getId());
            loadNextReviewWord();
        });
        context.changes().subscribe(changes -> {
            if (changes.contains(DataChange.WORDS)) {
                loadNextReviewWord();
            }
        });
    }

    public Tab tab() {
        return tab;
    }

    /** Shows the first card of the current deck. */
    public void start() {
        loadNextReviewWord();
    }

    private VBox createContent() {
        reviewModeSelector = new ComboBox<>();
        reviewModeSelector.setId("reviewModeSelector");
        reviewModeSelector.getItems().setAll(ReviewMode.values());
        reviewModeSelector.setCellFactory(list -> reviewModeCell());
        reviewModeSelector.setButtonCell(reviewModeCell());
        reviewModeSelector.getSelectionModel().select(ReviewMode.EN_TO_ZH);
        reviewModeSelector.valueProperty().addListener((observable, oldMode, newMode) ->
            context.errors().guard("Change review mode failed", () -> {
                reviewService.resetSession(context.decks().currentId());
                loadNextReviewWord();
            }));
        sessionSizeSelector = new ComboBox<>();
        sessionSizeSelector.setId("sessionSizeSelector");
        sessionSizeSelector.getItems().setAll("10", "20", "50", "All Due", "Custom");
        sessionSizeSelector.getSelectionModel().select("20");
        customSessionSizeField = new TextField();
        customSessionSizeField.setId("customSessionSizeField");
        customSessionSizeField.setPromptText("Custom");
        customSessionSizeField.setPrefWidth(90);
        customSessionSizeField.setDisable(true);
        sessionSizeSelector.valueProperty().addListener((observable, oldValue, newValue) ->
            customSessionSizeField.setDisable(!"Custom".equals(newValue)));
        Button startSessionButton = new Button("Start Session");
        startSessionButton.setId("startSessionButton");
        startSessionButton.setOnAction(event -> startReviewSession());
        Button resetSessionButton = new Button("Reset Session");
        resetSessionButton.setId("resetSessionButton");
        resetSessionButton.setOnAction(event -> context.errors().guard("Reset session failed", () -> {
            reviewService.resetSession(context.decks().currentId());
            loadNextReviewWord();
        }));
        sessionProgressLabel = new Label();
        sessionProgressLabel.setId("sessionProgressLabel");
        sessionProgressLabel.setStyle("-fx-text-fill: #4b5563;");
        HBox modeBox = new HBox(10, new Label("Mode"), reviewModeSelector, sessionProgressLabel);
        modeBox.setAlignment(Pos.CENTER_LEFT);
        HBox sessionBox = new HBox(10, new Label("Session size"), sessionSizeSelector, customSessionSizeField,
            startSessionButton, resetSessionButton);
        sessionBox.setAlignment(Pos.CENTER_LEFT);

        reviewWordLabel = new Label("Loading...");
        reviewWordLabel.setId("reviewWordLabel");
        reviewWordLabel.setStyle("-fx-font-size: 34px; -fx-font-weight: 700;");
        reviewMetaLabel = new Label();
        reviewMetaLabel.setId("reviewMetaLabel");
        reviewMetaLabel.setStyle("-fx-text-fill: #4b5563;");
        answerField = new TextField();
        answerField.setId("answerField");
        answerField.setPromptText("Enter Chinese meaning");
        answerField.setPrefWidth(420);
        submitAnswerButton = new Button("Submit");
        submitAnswerButton.setId("submitAnswerButton");
        submitAnswerButton.setOnAction(event -> context.errors().guard("Submit answer failed", this::submitCurrentAnswer));
        answerField.setOnAction(event -> context.errors().guard("Submit answer failed", this::submitCurrentAnswer));

        reviewResultArea = new TextArea();
        reviewResultArea.setId("reviewResultArea");
        reviewResultArea.setEditable(false);
        reviewResultArea.setWrapText(true);
        reviewResultArea.setPrefRowCount(8);

        completionTitleLabel = new Label("Review complete");
        completionTitleLabel.setId("completionTitleLabel");
        completionTitleLabel.setStyle("-fx-font-size: 22px; -fx-font-weight: 700;");
        completionMetricsLabel = new Label();
        completionMetricsLabel.setId("completionMetricsLabel");
        completionMetricsLabel.setWrapText(true);
        completionCard = new VBox(8, completionTitleLabel, completionMetricsLabel);
        completionCard.setId("completionCard");
        completionCard.setPadding(new Insets(16));
        completionCard.setStyle("-fx-background-color: #ecfdf5; -fx-border-color: #10b981; -fx-border-radius: 6; -fx-background-radius: 6;");
        completionCard.setVisible(false);
        completionCard.setManaged(false);

        ratingButtons = new HBox(10,
            ratingButton(ReviewRating.AGAIN),
            ratingButton(ReviewRating.HARD),
            ratingButton(ReviewRating.GOOD),
            ratingButton(ReviewRating.EASY)
        );
        ratingButtons.setId("ratingButtons");
        ratingButtons.setDisable(true);

        HBox answerBox = new HBox(10, answerField, submitAnswerButton);
        answerBox.setAlignment(Pos.CENTER_LEFT);
        VBox content = new VBox(16, modeBox, sessionBox, reviewWordLabel, reviewMetaLabel, answerBox,
            completionCard, reviewResultArea, ratingButtons);
        content.setPadding(new Insets(28));
        VBox.setVgrow(reviewResultArea, Priority.ALWAYS);
        return content;
    }

    /** The id of a rating button: rateAgainButton, rateHardButton, rateGoodButton or rateEasyButton. */
    private static String ratingButtonId(ReviewRating rating) {
        String name = rating.name();
        return "rate" + name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT) + "Button";
    }

    private static ListCell<ReviewMode> reviewModeCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(ReviewMode item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getLabel());
            }
        };
    }

    private Button ratingButton(ReviewRating rating) {
        Button button = new Button(rating.getLabel());
        button.setId(ratingButtonId(rating));
        button.setMinWidth(90);
        button.setOnAction(event -> rateCurrentWord(rating));
        return button;
    }

    private void startReviewSession() {
        try {
            int target = selectedSessionTarget();
            reviewService.startSession(context.decks().currentId(), currentReviewMode(), target);
            loadNextReviewWord();
        } catch (RuntimeException e) {
            context.errors().reportFailure("Start session failed", e);
        }
    }

    private int selectedSessionTarget() {
        String value = sessionSizeSelector.getValue();
        if ("All Due".equals(value)) {
            return 0;
        }
        if ("Custom".equals(value)) {
            String text = customSessionSizeField.getText().trim();
            int custom = Integer.parseInt(text);
            if (custom <= 0 || custom > 500) {
                throw new IllegalArgumentException("Custom session size must be between 1 and 500.");
            }
            return custom;
        }
        return Integer.parseInt(value == null ? "20" : value);
    }

    private void submitCurrentAnswer() {
        if (currentReviewWord == null || submitAnswerButton.isDisabled()) {
            return;
        }
        ReviewAnswer answer = reviewService.submitAnswer(currentReviewWord.getId(), answerField.getText(), answerMode());
        WordCard wordForAi = currentReviewWord;
        AiService aiService = configured.ai();
        String baseResult = "Correct answer: " + answer.correctAnswer()
            + System.lineSeparator() + "Your answer: " + answer.userAnswer()
            + System.lineSeparator() + "Answer similarity: " + Formats.percent(answer.similarity());
        reviewResultArea.setText(baseResult + System.lineSeparator() + System.lineSeparator() + "AI explanation: loading...");
        context.async().run(
            () -> aiService.explain(wordForAi),
            explanation -> {
                if (currentReviewWord != null && currentReviewWord.getId() == wordForAi.getId()) {
                    reviewResultArea.setText(baseResult + System.lineSeparator() + System.lineSeparator() + explanation);
                }
            },
            error -> reviewResultArea.setText(baseResult + System.lineSeparator() + System.lineSeparator()
                + "AI explanation unavailable: " + UiErrors.rootMessage(error))
        );
        ratingButtons.setDisable(false);
        submitAnswerButton.setDisable(true);
        answerField.setDisable(true);
    }

    private void rateCurrentWord(ReviewRating rating) {
        if (currentReviewWord == null || ratingButtons.isDisabled()) {
            return;
        }
        // Disarm the buttons while saving so the same card cannot be rated twice.
        ratingButtons.setDisable(true);
        long wordId = currentReviewWord.getId();
        ReviewOutcome outcome;
        try {
            outcome = reviewService.rateCurrent(wordId, rating);
        } catch (RuntimeException e) {
            // Nothing was saved. The service keeps the submitted answer, so the same card and
            // answer stay on screen and the user can simply rate again.
            if (reviewService.hasPendingAnswer(wordId)) {
                context.errors().reportFailure("Rating not saved - choose a rating again to retry", e);
                ratingButtons.setDisable(false);
            } else {
                context.errors().reportFailure("Rating not saved - submit your answer again", e);
                submitAnswerButton.setDisable(false);
                answerField.setDisable(false);
                reviewResultArea.setText("The rating was not saved. Submit your answer again to retry.");
            }
            return;
        }
        context.errors().guard("Rating saved, but refreshing the review failed", () -> {
            loadNextReviewWord();
            if (currentReviewWord != null) {
                reviewResultArea.setText("Saved. XP +" + outcome.xpEarned() + Formats.unlockedSuffix(outcome.unlockedAchievements()));
            }
            context.changes().publish(DataChange.REVIEWS);
        });
    }

    private void loadNextReviewWord() {
        Optional<WordCard> next = reviewService.nextWord(context.decks().currentId(), currentReviewMode());
        currentReviewWord = next.orElse(null);
        answerField.clear();
        reviewResultArea.clear();
        completionCard.setVisible(false);
        completionCard.setManaged(false);
        ratingButtons.setDisable(true);
        submitAnswerButton.setDisable(currentReviewWord == null);
        answerField.setDisable(currentReviewWord == null);
        answerField.setPromptText(answerMode().getPrompt());
        updateSessionProgress();
        if (currentReviewWord == null) {
            showReviewCompletion();
        } else {
            reviewWordLabel.setText(answerMode() == ReviewMode.ZH_TO_EN
                ? currentReviewWord.getChinese()
                : currentReviewWord.getEnglish());
            reviewMetaLabel.setText(answerMode().getLabel()
                + " | Streak " + currentReviewWord.getConsecutiveCorrect()
                + " | Interval " + currentReviewWord.getIntervalDays()
                + " days | EF " + String.format("%.2f", currentReviewWord.getEasinessFactor())
                + " | Lapses " + currentReviewWord.getLapses());
            answerField.requestFocus();
        }
    }

    private void showReviewCompletion() {
        ReviewSessionSummary session = reviewService.sessionSummary();
        DailyGoalProgress progress = goalService.getTodayProgress(context.decks().currentId());
        reviewWordLabel.setText("Review complete");
        boolean targetReached = session.sessionGoal() > 0 && session.reviewedCount() >= session.sessionGoal();
        reviewMetaLabel.setText(targetReached ? "Session target reached." : "No due words right now.");
        completionTitleLabel.setText("Session Complete");
        completionMetricsLabel.setText("Completed: " + session.reviewedCount()
            + (session.sessionGoal() > 0 ? "/" + session.sessionGoal() : " / All Due")
            + " | Accuracy: " + Formats.percent(session.accuracy())
            + " | XP: " + session.xpEarned()
            + System.lineSeparator() + "Today review goal: " + progress.reviewedCount() + "/" + progress.reviewGoal()
            + " | New words: " + progress.newWordsCount() + "/" + progress.newWordGoal()
            + System.lineSeparator() + "Unlocked: " + Formats.achievementNames(session.unlockedAchievements()));
        completionCard.setVisible(true);
        completionCard.setManaged(true);
        reviewResultArea.setText("Use Weak Words mode to keep working on your most fragile cards, or switch deck from the header.");
    }

    private ReviewMode currentReviewMode() {
        return reviewModeSelector.getValue() == null ? ReviewMode.EN_TO_ZH : reviewModeSelector.getValue();
    }

    private ReviewMode answerMode() {
        return reviewService.currentQuestionMode();
    }

    private void updateSessionProgress() {
        ReviewSessionSummary session = reviewService.sessionSummary();
        String target = session.sessionGoal() > 0 ? String.valueOf(session.sessionGoal()) : "All Due";
        sessionProgressLabel.setText("Session " + session.reviewedCount() + "/" + target
            + " | Accuracy " + Formats.percent(session.accuracy())
            + " | XP " + session.xpEarned());
    }
}
