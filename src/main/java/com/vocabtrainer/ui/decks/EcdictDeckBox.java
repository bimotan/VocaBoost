package com.vocabtrainer.ui.decks;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.repository.EcdictRepository;
import com.vocabtrainer.service.ecdict.EcdictTagDeckService;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.UiAsync;
import com.vocabtrainer.ui.UiErrors;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "Create deck from ECDICT tag" on the Decks tab: a form for the exam tag, the deck to create or
 * fill, a limit and the order, then the words are added in the background with a progress bar and
 * Cancel. The deck it built becomes the current deck.
 */
final class EcdictDeckBox {
    private final ViewContext context;
    private final EcdictTagDeckService service;
    private final Button createButton = new Button("Create deck from ECDICT tag...");
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Button cancelButton = new Button("Cancel");
    private final HBox progressRow;
    private final Label statusLabel = new Label();
    private final VBox root;

    EcdictDeckBox(ViewContext context, EcdictTagDeckService service) {
        this.context = context;
        this.service = service;
        createButton.setId("ecdictDeckButton");
        createButton.setOnAction(event -> context.errors().guard("Create deck from ECDICT failed", this::askAndBuild));
        progressBar.setId("ecdictDeckProgressBar");
        progressBar.setMaxWidth(Double.MAX_VALUE);
        cancelButton.setId("cancelEcdictDeckButton");
        progressRow = new HBox(10, progressBar, cancelButton);
        progressRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(progressBar, Priority.ALWAYS);
        showProgressRow(false);
        statusLabel.setId("ecdictDeckStatusLabel");
        statusLabel.setWrapText(true);
        statusLabel.setMinHeight(Region.USE_PREF_SIZE);
        root = new VBox(8, createButton, progressRow, statusLabel);
    }

    Node root() {
        return root;
    }

    private void askAndBuild() {
        if (!service.isAvailable()) {
            context.errors().showInfo("Creating a deck from an ECDICT tag needs the ECDICT dictionary. Import"
                + " ecdict.csv in the ECDICT box of the Settings tab first; it is imported once and then works"
                + " offline.");
            return;
        }
        Optional<EcdictTagDeckService.Request> request = askRequest();
        request.ifPresent(this::build);
    }

    /** The form; empty when it is cancelled or not filled in correctly (which is shown). */
    private Optional<EcdictTagDeckService.Request> askRequest() {
        ComboBox<EcdictTagDeckService.Tag> tagSelector =
            new ComboBox<>(FXCollections.observableArrayList(EcdictTagDeckService.Tag.values()));
        tagSelector.setId("ecdictTagSelector");
        tagSelector.setValue(EcdictTagDeckService.Tag.GRE);

        ComboBox<String> deckName = new ComboBox<>(FXCollections.observableArrayList(
            context.decks().activeDecks().stream().map(Deck::getName).toList()));
        deckName.setId("ecdictDeckNameField");
        deckName.setEditable(true);
        deckName.setPrefWidth(260);
        setDeckName(deckName, EcdictTagDeckService.Tag.GRE.defaultDeckName());
        // A name the user has not changed follows the tag.
        tagSelector.valueProperty().addListener((observable, oldTag, newTag) -> {
            if (oldTag != null && newTag != null && oldTag.defaultDeckName().equals(deckNameText(deckName))) {
                setDeckName(deckName, newTag.defaultDeckName());
            }
        });

        TextField limitField = new TextField();
        limitField.setId("ecdictLimitField");
        limitField.setPromptText("all");
        limitField.setPrefColumnCount(6);
        limitField.setTextFormatter(new TextFormatter<String>(change ->
            change.getControlNewText().matches("\\d{0,5}") ? change : null));

        ToggleGroup order = new ToggleGroup();
        RadioButton byFrequency = new RadioButton("Most common first");
        byFrequency.setId("ecdictOrderFrequency");
        byFrequency.setToggleGroup(order);
        byFrequency.setSelected(true);
        RadioButton alphabetical = new RadioButton("Alphabetical");
        alphabetical.setId("ecdictOrderAlphabetical");
        alphabetical.setToggleGroup(order);

        Label hint = Widgets.hint("A new deck is created; when an active deck has this name, the words are added to it."
            + " Words already in the deck are skipped, and the limit counts the words added. Meanings, parts of"
            + " speech and phonetics come from ECDICT, cleaned like a lookup.");
        // A fixed width lets the grid give the wrapped lines their height.
        hint.setPrefWidth(400);

        GridPane form = new GridPane();
        form.setId("ecdictDeckForm");
        form.setHgap(10);
        form.setVgap(10);
        form.add(Widgets.formLabel("Exam _tag", tagSelector), 0, 0);
        form.add(tagSelector, 1, 0);
        form.add(Widgets.formLabel("_Deck", deckName), 0, 1);
        form.add(deckName, 1, 1);
        form.add(Widgets.formLabel("_Limit", limitField), 0, 2);
        form.add(new HBox(8, limitField, new Label("words (empty for all)")), 1, 2);
        form.add(Widgets.formLabel("_Order", byFrequency), 0, 3);
        form.add(new HBox(16, byFrequency, alphabetical), 1, 3);
        form.add(hint, 1, 4);

        if (!context.dialogs().showForm("Create deck from ECDICT tag", form)) {
            return Optional.empty();
        }
        try {
            String limit = limitField.getText() == null ? "" : limitField.getText().trim();
            return Optional.of(new EcdictTagDeckService.Request(
                tagSelector.getValue(),
                deckNameText(deckName),
                limit.isEmpty() ? 0 : Integer.parseInt(limit),
                alphabetical.isSelected() ? EcdictRepository.TagOrder.ALPHABETICAL : EcdictRepository.TagOrder.FREQUENCY));
        } catch (IllegalArgumentException e) {
            context.errors().showError("Deck not created", e.getMessage());
            return Optional.empty();
        }
    }

    /** Builds the deck in the background; the deck is captured in the request, so a deck switch meanwhile does not matter. */
    private void build(EcdictTagDeckService.Request request) {
        AtomicBoolean cancelRequested = new AtomicBoolean();
        Task<EcdictTagDeckService.Result> task = new Task<>() {
            @Override
            protected EcdictTagDeckService.Result call() {
                return service.build(request, progress -> {
                    updateProgress(progress.total() < 0 ? -1 : progress.added(), Math.max(1, progress.total()));
                    updateMessage(progress.toDisplayText());
                }, cancelRequested::get);
            }
        };
        String startMessage = "Building " + request.deckName() + " from the ECDICT " + request.tag().code() + " words...";
        statusLabel.setText(startMessage);
        task.messageProperty().addListener((observable, oldMessage, progressMessage) -> {
            if (!cancelRequested.get() && progressMessage != null && !progressMessage.isBlank()) {
                statusLabel.setText(startMessage + System.lineSeparator() + progressMessage);
            }
        });
        progressBar.progressProperty().bind(task.progressProperty());
        cancelButton.setDisable(false);
        cancelButton.setOnAction(event -> {
            cancelRequested.set(true);
            cancelButton.setDisable(true);
            statusLabel.setText("Canceling...");
        });
        showProgressRow(true);
        createButton.setDisable(true);

        task.setOnSucceeded(event -> {
            finished();
            EcdictTagDeckService.Result result = task.getValue();
            statusLabel.setText(result.toDisplayText(request.tag()));
            if (result.deck() != null) {
                context.errors().guard("Deck built, but refreshing the views failed", () -> {
                    context.decks().switchTo(result.deck());
                    context.changes().publish(DataChange.DECKS, DataChange.WORDS);
                });
            }
        });
        task.setOnFailed(event -> {
            finished();
            Throwable error = task.getException();
            if (error instanceof CancellationException) {
                statusLabel.setText("Canceled. Nothing was added.");
                return;
            }
            context.errors().logFailure("Create deck from ECDICT failed", error);
            String message = error.getMessage() == null || error.getMessage().isBlank()
                ? UiErrors.rootMessage(error) : error.getMessage();
            statusLabel.setText("Failed: " + message + " Nothing was added.");
        });
        Thread thread = new Thread(task, UiAsync.THREAD_NAME);
        thread.setDaemon(true);
        thread.start();
    }

    private void finished() {
        progressBar.progressProperty().unbind();
        showProgressRow(false);
        createButton.setDisable(false);
    }

    private void showProgressRow(boolean visible) {
        progressRow.setVisible(visible);
        progressRow.setManaged(visible);
        progressBar.setVisible(visible);
        cancelButton.setVisible(visible);
    }

    private static void setDeckName(ComboBox<String> deckName, String name) {
        deckName.setValue(name);
        deckName.getEditor().setText(name);
    }

    /** The name typed or chosen, which OK takes even if it was not committed yet. */
    private static String deckNameText(ComboBox<String> deckName) {
        String typed = deckName.getEditor().getText();
        return typed == null ? "" : typed.trim();
    }
}
