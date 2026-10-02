package com.vocabtrainer.ui.review;

import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.service.GoalService;
import com.vocabtrainer.service.ReviewService;
import com.vocabtrainer.ui.ConfiguredServices;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import javafx.event.EventTarget;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tab;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.Clock;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The Review tab. It only shows the state of a {@link ReviewSessionPresenter} and forwards the
 * user's input to it; the review flow itself lives in the presenter.
 *
 * <p>Once an answer is checked, each rating button also shows when that rating would bring the card
 * back, e.g. "Good (3) · 4d".
 *
 * <p>Keyboard: Enter in the answer field submits. Once the answer is checked, 1, 2, 3 and 4 rate
 * Again, Hard, Good and Easy and Space rates Good (or presses the focused button, which is Good
 * right after submitting), while the Review tab is shown and the user is not typing in another field.
 */
public final class ReviewView {
    private final ViewContext context;
    private final ReviewSessionPresenter presenter;

    private final ComboBox<ReviewMode> reviewModeSelector = new ComboBox<>();
    private final ComboBox<String> sessionSizeSelector = new ComboBox<>();
    private final TextField customSessionSizeField = new TextField();
    private final Label sessionProgressLabel = new Label();
    private final Label reviewWordLabel = new Label(ReviewSessionPresenter.LOADING);
    private final Label reviewMetaLabel = new Label();
    private final TextField answerField = new TextField();
    private final Button submitAnswerButton = new Button("Submit");
    private final TextArea reviewResultArea = new TextArea();
    private final Label completionTitleLabel = new Label("Review complete");
    private final Label completionMetricsLabel = new Label();
    private final VBox completionCard = new VBox(8, completionTitleLabel, completionMetricsLabel);
    private final HBox ratingButtons = new HBox(10);
    private final Map<ReviewRating, Button> ratingButtonsByRating = new EnumMap<>(ReviewRating.class);
    private final Tab tab;
    private long renderedCardNumber = -1;
    private boolean renderedCanRate;
    private boolean swallowTypedKey;

    /** {@code clock} times the answers. */
    public ReviewView(ViewContext context, ReviewService reviewService, GoalService goalService,
                      ConfiguredServices configured, Clock clock) {
        this.context = context;
        this.presenter = new ReviewSessionPresenter(reviewService, goalService, configured::ai, context.async(),
            context.changes(), context.errors()::reportFailure, clock);
        this.tab = Widgets.tab("reviewTab", "Review", createContent());
        presenter.addListener(this::render);
        // Answers are only timed while the tab is shown; the first card is loaded behind the Dashboard.
        presenter.setOnScreen(tab.isSelected());
        tab.selectedProperty().addListener((observable, wasSelected, selected) -> presenter.setOnScreen(selected));
        context.decks().onSwitch(deck -> presenter.showDeck(deck.getId()));
        context.changes().subscribe(changes -> {
            if (changes.contains(DataChange.WORDS)) {
                presenter.wordsChanged();
            }
        });
    }

    public Tab tab() {
        return tab;
    }

    /** Shows the first card of the current deck. */
    public void start() {
        presenter.showDeck(context.decks().currentId());
    }

    /** Lets {@code scene} rate the answered card with the keyboard; see the class comment. */
    public void installShortcuts(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::rateWithKey);
        // A key press is followed by a key-typed event, delivered to whatever has the focus by then:
        // after a rating that is the next card's answer field, which must not receive the "3".
        scene.addEventFilter(KeyEvent.KEY_TYPED, event -> {
            if (swallowTypedKey) {
                swallowTypedKey = false;
                event.consume();
            }
        });
        scene.addEventFilter(KeyEvent.KEY_RELEASED, event -> swallowTypedKey = false);
    }

    /** The digit key that gives {@code rating}; {@link #ratingForKey} maps it back. */
    static String shortcutKey(ReviewRating rating) {
        return switch (rating) {
            case AGAIN -> "1";
            case HARD -> "2";
            case GOOD -> "3";
            case EASY -> "4";
        };
    }

    /** The rating a key gives once an answer is checked: 1-4 (main row or keypad), or Space for Good. */
    static Optional<ReviewRating> ratingForKey(KeyCode code) {
        return switch (code) {
            case DIGIT1, NUMPAD1 -> Optional.of(ReviewRating.AGAIN);
            case DIGIT2, NUMPAD2 -> Optional.of(ReviewRating.HARD);
            case DIGIT3, NUMPAD3, SPACE -> Optional.of(ReviewRating.GOOD);
            case DIGIT4, NUMPAD4 -> Optional.of(ReviewRating.EASY);
            default -> Optional.empty();
        };
    }

    private void rateWithKey(KeyEvent event) {
        // A new key press: the typed event of an earlier rating key is over, even if it never came
        // here (e.g. an error dialog opened by that rating took it).
        swallowTypedKey = false;
        if (!tab.isSelected() || !presenter.canRate() || event.isShortcutDown() || event.isControlDown()
            || event.isAltDown() || event.isMetaDown() || isTypingIn(event.getTarget())) {
            return;
        }
        if (event.getCode() == KeyCode.SPACE && usesSpace(event.getTarget())) {
            return;
        }
        Optional<ReviewRating> rating = ratingForKey(event.getCode());
        if (rating.isPresent()) {
            event.consume();
            swallowTypedKey = true;
            presenter.rate(rating.get());
        }
    }

    /**
     * Space presses the focused button, e.g. Again after the user tabbed to it, or opens the focused
     * combo box, as everywhere else; it only means Good when the focus is elsewhere.
     */
    private static boolean usesSpace(EventTarget target) {
        return target instanceof ButtonBase || target instanceof ComboBoxBase<?>;
    }

    /** Digits typed into a field, such as the custom session size, are text, not ratings. */
    private static boolean isTypingIn(EventTarget target) {
        return target instanceof TextInputControl input && input.isEditable() && !input.isDisabled();
    }

    private VBox createContent() {
        reviewModeSelector.setId("reviewModeSelector");
        reviewModeSelector.getItems().setAll(ReviewMode.values());
        reviewModeSelector.setCellFactory(list -> reviewModeCell());
        reviewModeSelector.setButtonCell(reviewModeCell());
        reviewModeSelector.getSelectionModel().select(ReviewMode.EN_TO_ZH);
        reviewModeSelector.valueProperty().addListener((observable, oldMode, newMode) ->
            context.errors().guard("Change review mode failed", () -> presenter.changeMode(newMode)));
        sessionSizeSelector.setId("sessionSizeSelector");
        sessionSizeSelector.getItems().setAll("10", "20", "50", "All Due", "Custom");
        sessionSizeSelector.getSelectionModel().select("20");
        customSessionSizeField.setId("customSessionSizeField");
        customSessionSizeField.setPromptText("Custom");
        customSessionSizeField.setPrefWidth(90);
        customSessionSizeField.setDisable(true);
        sessionSizeSelector.valueProperty().addListener((observable, oldValue, newValue) ->
            customSessionSizeField.setDisable(!"Custom".equals(newValue)));
        Button startSessionButton = new Button("Start Session");
        startSessionButton.setId("startSessionButton");
        startSessionButton.setOnAction(event -> context.errors().guard("Start session failed",
            () -> presenter.startSession(sessionSizeSelector.getValue(), customSessionSizeField.getText())));
        Button resetSessionButton = new Button("Reset Session");
        resetSessionButton.setId("resetSessionButton");
        resetSessionButton.setOnAction(event -> context.errors().guard("Reset session failed", presenter::resetSession));
        sessionProgressLabel.setId("sessionProgressLabel");
        sessionProgressLabel.setStyle("-fx-text-fill: #4b5563;");
        HBox modeBox = new HBox(10, new Label("Mode"), reviewModeSelector, sessionProgressLabel);
        modeBox.setAlignment(Pos.CENTER_LEFT);
        HBox sessionBox = new HBox(10, new Label("Session size"), sessionSizeSelector, customSessionSizeField,
            startSessionButton, resetSessionButton);
        sessionBox.setAlignment(Pos.CENTER_LEFT);

        reviewWordLabel.setId("reviewWordLabel");
        reviewWordLabel.setStyle("-fx-font-size: 34px; -fx-font-weight: 700;");
        reviewMetaLabel.setId("reviewMetaLabel");
        reviewMetaLabel.setStyle("-fx-text-fill: #4b5563;");
        answerField.setId("answerField");
        answerField.setPromptText("Enter Chinese meaning");
        answerField.setPrefWidth(420);
        answerField.textProperty().addListener((observable, oldText, newText) -> presenter.setAnswer(newText));
        submitAnswerButton.setId("submitAnswerButton");
        submitAnswerButton.setTooltip(new Tooltip("Press Enter in the answer field"));
        submitAnswerButton.setOnAction(event -> context.errors().guard("Submit answer failed", presenter::submit));
        answerField.setOnAction(event -> context.errors().guard("Submit answer failed", presenter::submit));

        reviewResultArea.setId("reviewResultArea");
        reviewResultArea.setEditable(false);
        reviewResultArea.setWrapText(true);
        reviewResultArea.setPrefRowCount(8);

        completionTitleLabel.setId("completionTitleLabel");
        completionTitleLabel.setStyle("-fx-font-size: 22px; -fx-font-weight: 700;");
        completionMetricsLabel.setId("completionMetricsLabel");
        completionMetricsLabel.setWrapText(true);
        completionCard.setId("completionCard");
        completionCard.setPadding(new Insets(16));
        completionCard.setStyle("-fx-background-color: #ecfdf5; -fx-border-color: #10b981; -fx-border-radius: 6; -fx-background-radius: 6;");
        completionCard.setVisible(false);
        completionCard.setManaged(false);

        for (ReviewRating rating : ReviewRating.values()) {
            ratingButtons.getChildren().add(ratingButton(rating));
        }
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

    /** "Good (3)", and once the answer is checked the interval it gives, as in "Good (3) · 4d". */
    private static String ratingButtonText(ReviewRating rating, String preview) {
        String text = rating.getLabel() + " (" + shortcutKey(rating) + ")";
        return preview.isEmpty() ? text : text + " · " + preview;
    }

    private Button ratingButton(ReviewRating rating) {
        String key = shortcutKey(rating);
        Button button = new Button(ratingButtonText(rating, ""));
        button.setId(ratingButtonId(rating));
        button.setMinWidth(90);
        button.setTooltip(new Tooltip(rating == ReviewRating.GOOD ? "Press " + key + " or Space" : "Press " + key));
        button.setOnAction(event -> presenter.rate(rating));
        ratingButtonsByRating.put(rating, button);
        return button;
    }

    private void render() {
        reviewWordLabel.setText(presenter.question());
        reviewMetaLabel.setText(presenter.details());
        sessionProgressLabel.setText(presenter.sessionProgress());
        answerField.setPromptText(presenter.answerPrompt());
        if (!answerField.getText().equals(presenter.answer())) {
            answerField.setText(presenter.answer());
        }
        reviewResultArea.setText(presenter.result());
        completionTitleLabel.setText(presenter.completionTitle());
        completionMetricsLabel.setText(presenter.completionMetrics());
        completionCard.setVisible(presenter.isComplete());
        completionCard.setManaged(presenter.isComplete());
        answerField.setDisable(!presenter.canSubmit());
        submitAnswerButton.setDisable(!presenter.canSubmit());
        ratingButtons.setDisable(!presenter.canRate());
        ratingButtonsByRating.forEach((rating, button) ->
            button.setText(ratingButtonText(rating, presenter.ratingPreview(rating))));
        if (presenter.cardNumber() != renderedCardNumber && presenter.canSubmit()) {
            renderedCardNumber = presenter.cardNumber();
            answerField.requestFocus();
        }
        if (presenter.canRate() && !renderedCanRate) {
            // The answer field is disabled now; keep the keyboard on the rating buttons.
            ratingButtonsByRating.get(ReviewRating.GOOD).requestFocus();
        }
        renderedCanRate = presenter.canRate();
    }
}
