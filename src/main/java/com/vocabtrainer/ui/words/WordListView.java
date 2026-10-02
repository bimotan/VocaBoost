package com.vocabtrainer.ui.words;

import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.ReviewScheduler;
import com.vocabtrainer.service.WordValidationService;
import com.vocabtrainer.service.cloze.ClozeMaker;
import com.vocabtrainer.service.scheduling.StudyDay;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.Formats;
import com.vocabtrainer.ui.LazyRefresh;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import com.vocabtrainer.ui.WordDetails;
import com.vocabtrainer.ui.WordDetailsCard;
import com.vocabtrainer.util.DateTimeUtil;
import javafx.animation.PauseTransition;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tab;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Word List tab: search and filter the current deck's words, suspended ones included, edit one,
 * and suspend, unsuspend or delete the selected ones. Deleting asks first, says that the words'
 * review history goes with them and offers to suspend them instead. Under the table a card shows the
 * selected word's phonetic, part of speech, example (the word in bold), note and tags.
 */
public final class WordListView {
    private final ViewContext context;
    private final WordRepository wordRepository;
    private final ReviewLogRepository reviewLogRepository;
    private final Clock clock;
    private final StudyDay studyDay;
    private final WordEditDialog editDialog;
    private final ClozeMaker examples;
    private final WordDetailsCard detailsCard = new WordDetailsCard("wordDetails");
    private final ObservableList<WordCard> wordItems = FXCollections.observableArrayList();
    private final TableView<WordCard> wordTable = new TableView<>(wordItems);
    private final TextField searchField = new TextField();
    private final ComboBox<String> wordStatusFilter = new ComboBox<>();
    private final TextField tagFilterField = new TextField();
    private final TextField posFilterField = new TextField();
    private final Button suspendButton = new Button("Suspend");
    private final Button unsuspendButton = new Button("Unsuspend");
    private final Tab tab;
    private final LazyRefresh lazy;

    /**
     * {@code clock} and {@code studyDay} decide which words are due today and how strong their memory
     * is; {@code examples} finds the word in its example sentence.
     */
    public WordListView(ViewContext context, WordRepository wordRepository, ReviewLogRepository reviewLogRepository,
                        WordValidationService validationService, Clock clock, StudyDay studyDay, ClozeMaker examples) {
        this.context = context;
        this.examples = examples;
        this.wordRepository = wordRepository;
        this.reviewLogRepository = reviewLogRepository;
        this.clock = clock;
        this.studyDay = studyDay;
        this.editDialog = new WordEditDialog(context, wordRepository, validationService);
        this.tab = Widgets.tab("wordListTab", "Word List", createContent());
        this.lazy = new LazyRefresh(tab, this::refresh, context.errors(), "Refresh failed", false);
        context.changes().subscribe(changes -> {
            if (changes.contains(DataChange.WORDS) || changes.contains(DataChange.REVIEWS)) {
                lazy.markStale();
            }
        });
        context.decks().onSwitch(deck -> lazy.markStale());
    }

    public Tab tab() {
        return tab;
    }

    /** Loads the content now, whether the tab is shown or not. */
    public void refreshNow() {
        lazy.refreshNow();
    }

    private VBox createContent() {
        searchField.setId("wordSearchField");
        searchField.setPromptText("Search English, Chinese or tags");
        wordStatusFilter.setId("wordStatusFilter");
        wordStatusFilter.getItems().setAll(WordListFilter.STATUSES);
        wordStatusFilter.getSelectionModel().select("All");
        tagFilterField.setId("wordTagFilterField");
        tagFilterField.setPromptText("Tag");
        tagFilterField.setPrefWidth(120);
        posFilterField.setId("wordPosFilterField");
        posFilterField.setPromptText("POS");
        posFilterField.setPrefWidth(120);
        PauseTransition searchDebounce = new PauseTransition(Duration.millis(250));
        searchDebounce.setOnFinished(event -> lazy.refreshNow());
        searchField.textProperty().addListener((observable, oldValue, newValue) -> searchDebounce.playFromStart());
        tagFilterField.textProperty().addListener((observable, oldValue, newValue) -> searchDebounce.playFromStart());
        posFilterField.textProperty().addListener((observable, oldValue, newValue) -> searchDebounce.playFromStart());
        wordStatusFilter.valueProperty().addListener((observable, oldValue, newValue) -> lazy.refreshNow());
        Button refreshButton = new Button("Refresh");
        refreshButton.setId("refreshWordsButton");
        refreshButton.setOnAction(event -> lazy.refreshNow());
        Button editButton = new Button("Edit selected");
        editButton.setId("editWordButton");
        editButton.setOnAction(event -> editSelectedWord());
        Button deleteButton = new Button("Delete selected");
        deleteButton.setId("deleteWordButton");
        deleteButton.setOnAction(event -> deleteSelectedWords());
        suspendButton.setId("suspendWordsButton");
        suspendButton.setTooltip(new Tooltip("暂停: stop reviewing the selected words, keeping them and their"
            + " history"));
        suspendButton.setOnAction(event -> setSelectedSuspended(true));
        unsuspendButton.setId("unsuspendWordsButton");
        unsuspendButton.setTooltip(new Tooltip("Review the selected suspended words again"));
        unsuspendButton.setOnAction(event -> setSelectedSuspended(false));

        HBox filters = new HBox(10, searchField, wordStatusFilter, tagFilterField, posFilterField, refreshButton);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        Label selectionHint = new Label("Shift- or Ctrl-click to select several words.");
        selectionHint.setStyle("-fx-text-fill: #6b7280;");
        HBox actions = new HBox(10, editButton, suspendButton, unsuspendButton, deleteButton, selectionHint);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox controls = new VBox(8, filters, actions);

        wordTable.setId("wordTable");
        wordTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        // Several words can be selected (Shift or Ctrl) to suspend, unsuspend or delete them together.
        wordTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        wordTable.getSelectionModel().getSelectedItems().addListener(
            (ListChangeListener<WordCard>) change -> updateSelectionActions());
        updateSelectionActions();
        TableColumn<WordCard, String> englishCol = new TableColumn<>("English");
        englishCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getEnglish()));
        TableColumn<WordCard, String> chineseCol = new TableColumn<>("Chinese");
        chineseCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getChinese()));
        TableColumn<WordCard, String> nextCol = new TableColumn<>("Next review");
        nextCol.setCellValueFactory(data -> new SimpleStringProperty(DateTimeUtil.toDisplay(data.getValue().getNextReviewAt())));
        TableColumn<WordCard, String> intervalCol = new TableColumn<>("Interval");
        intervalCol.setCellValueFactory(data -> new SimpleStringProperty(Formats.cardInterval(data.getValue())));
        // The chance of recalling the word now (FSRS retrievability); "New" before its first review.
        TableColumn<WordCard, String> strengthCol = new TableColumn<>("Memory");
        strengthCol.setCellValueFactory(data -> {
            OptionalDouble recall = ReviewScheduler.retrievability(data.getValue(), LocalDateTime.now(clock));
            return new SimpleStringProperty(recall.isPresent() ? Formats.percent(recall.getAsDouble()) : "New");
        });
        TableColumn<WordCard, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(data -> {
            LocalDateTime now = LocalDateTime.now(clock);
            return new SimpleStringProperty(WordListFilter.statusOf(data.getValue(), now, studyDay.end(now)));
        });
        wordTable.getColumns().addAll(List.of(englishCol, chineseCol, nextCol, intervalCol, strengthCol, statusCol));

        wordTable.getSelectionModel().selectedItemProperty().addListener(
            (observable, oldWord, word) -> showDetails(word));
        showDetails(null);

        VBox content = new VBox(12, controls, wordTable, detailsCard.root());
        content.setPadding(new Insets(24));
        VBox.setVgrow(wordTable, Priority.ALWAYS);
        return content;
    }

    private void refresh() {
        try {
            List<WordCard> words = wordRepository.search(context.decks().currentId(), searchField.getText());
            WordListFilter filter = new WordListFilter(wordStatusFilter.getValue(), tagFilterField.getText(),
                posFilterField.getText());
            LocalDateTime now = LocalDateTime.now(clock);
            LocalDateTime dayEnd = studyDay.end(now);
            WordCard selected = wordTable.getSelectionModel().getSelectedItem();
            Set<Long> selectedIds = selectedWords().stream().map(WordCard::getId).collect(Collectors.toSet());
            wordItems.setAll(words.stream().filter(word -> filter.matches(word, now, dayEnd)).toList());
            // Keep the selected words selected, with their details as they are now (e.g. after an edit);
            // the one whose details were shown is selected last, so they still are.
            // All at once: selecting thousands of rows one by one (after Ctrl+A) takes seconds.
            wordTable.getSelectionModel().clearSelection();
            int shownIndex = -1;
            List<Integer> others = new ArrayList<>();
            for (int index = 0; index < wordItems.size(); index++) {
                long id = wordItems.get(index).getId();
                if (selected != null && id == selected.getId()) {
                    shownIndex = index;
                } else if (selectedIds.contains(id)) {
                    others.add(index);
                }
            }
            if (!others.isEmpty()) {
                wordTable.getSelectionModel().selectIndices(others.get(0),
                    others.subList(1, others.size()).stream().mapToInt(Integer::intValue).toArray());
            }
            if (shownIndex >= 0) {
                wordTable.getSelectionModel().select(shownIndex);
            }
            showDetails(wordTable.getSelectionModel().getSelectedItem());
            updateSelectionActions();
        } catch (SQLException e) {
            context.errors().reportFailure("Refresh failed", e);
        }
    }

    private void showDetails(WordCard word) {
        if (word == null) {
            detailsCard.showMessage("Select a word to see its phonetic, part of speech, example, note and tags.");
            return;
        }
        detailsCard.show(word.getEnglish() + "   " + word.getChinese(),
            WordDetails.of(word, examples.highlight(word.getExampleSentence(), word.getEnglish())),
            "No phonetic, part of speech, example, note or tags yet: choose Edit selected to add them.");
    }

    private void editSelectedWord() {
        WordCard selected = wordTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            context.errors().showInfo("Please select a word to edit.");
            return;
        }
        if (editDialog.edit(selected)) {
            context.errors().guard("Save failed", () -> context.changes().publish(DataChange.WORDS));
        }
    }

    private List<WordCard> selectedWords() {
        return List.copyOf(wordTable.getSelectionModel().getSelectedItems());
    }

    /** Suspend is offered while a word in study is selected, Unsuspend while a suspended one is. */
    private void updateSelectionActions() {
        List<WordCard> selected = selectedWords();
        suspendButton.setDisable(selected.stream().allMatch(WordCard::isSuspended));
        unsuspendButton.setDisable(selected.stream().noneMatch(WordCard::isSuspended));
    }

    private void setSelectedSuspended(boolean suspended) {
        List<Long> ids = selectedWords().stream().map(WordCard::getId).toList();
        if (ids.isEmpty()) {
            context.errors().showInfo("Please select the words to " + (suspended ? "suspend." : "unsuspend."));
            return;
        }
        try {
            wordRepository.setSuspended(ids, suspended);
            context.changes().publish(DataChange.WORDS);
        } catch (SQLException | RuntimeException e) {
            context.errors().reportFailure(suspended ? "Suspend failed" : "Unsuspend failed", e);
        }
    }

    /**
     * Deletes the selected words after a confirmation that says their review history goes with them
     * and that only a backup brings them back, and that offers to suspend them instead.
     */
    private void deleteSelectedWords() {
        List<WordCard> selected = selectedWords();
        if (selected.isEmpty()) {
            context.errors().showInfo("Please select a word to delete.");
            return;
        }
        List<Long> ids = selected.stream().map(WordCard::getId).toList();
        try {
            int reviews = reviewLogRepository.countByWords(ids);
            boolean canSuspend = selected.stream().anyMatch(word -> !word.isSuspended());
            ButtonType delete = new ButtonType("Delete", ButtonBar.ButtonData.OK_DONE);
            ButtonType suspendInstead = new ButtonType("Suspend instead", ButtonBar.ButtonData.OTHER);
            ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            ButtonType[] buttons = canSuspend
                ? new ButtonType[] {delete, suspendInstead, cancel}
                : new ButtonType[] {delete, cancel};
            String header = selected.size() == 1
                ? "Delete \"" + selected.get(0).getEnglish() + "\"?"
                : "Delete " + selected.size() + " words?";
            Optional<ButtonType> choice = context.dialogs().choose(selected.size() == 1 ? "Delete word" : "Delete words",
                header, deleteWarning(selected.size(), reviews, canSuspend), buttons);
            if (choice.isEmpty() || choice.get() == cancel) {
                return;
            }
            if (choice.get() == suspendInstead) {
                wordRepository.setSuspended(ids, true);
            } else {
                wordRepository.deleteByIds(ids);
            }
            context.changes().publish(DataChange.WORDS);
        } catch (SQLException | RuntimeException e) {
            context.errors().reportFailure("Delete failed", e);
        }
    }

    /**
     * What deleting {@code words} words with {@code reviews} review logs between them loses, that only
     * a backup brings it back, and, when {@code canSuspend}, that suspending keeps it.
     */
    static String deleteWarning(int words, int reviews, boolean canSuspend) {
        boolean one = words == 1;
        String history = reviews == 0
            ? (one ? "It has no review history yet." : "They have no review history yet.")
            : (one ? "Its review history" : "Their review history") + " (" + reviews
                + (reviews == 1 ? " review" : " reviews") + ") will be deleted with "
                + (one ? "it" : "them") + ", and those reviews no longer count in the daily numbers and statistics.";
        String text = history + System.lineSeparator() + System.lineSeparator()
            + "Deleting cannot be undone: only restoring a JSON backup made before brings "
            + (one ? "it" : "them") + " back.";
        if (canSuspend) {
            text += System.lineSeparator() + System.lineSeparator() + "Suspend instead to stop reviewing "
                + (one ? "it" : "them") + " and keep the history; you can unsuspend " + (one ? "it" : "them")
                + " here at any time.";
        }
        return text;
    }
}
