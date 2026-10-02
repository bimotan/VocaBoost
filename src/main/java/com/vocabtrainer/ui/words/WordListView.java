package com.vocabtrainer.ui.words;

import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.WordValidationService;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.Formats;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import com.vocabtrainer.util.DateTimeUtil;
import javafx.animation.PauseTransition;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Tab;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/** The Word List tab: search and filter the current deck's words, edit or delete one. */
public final class WordListView {
    private final ViewContext context;
    private final WordRepository wordRepository;
    private final Clock clock;
    private final WordEditDialog editDialog;
    private final ObservableList<WordCard> wordItems = FXCollections.observableArrayList();
    private final TableView<WordCard> wordTable = new TableView<>(wordItems);
    private final TextField searchField = new TextField();
    private final ComboBox<String> wordStatusFilter = new ComboBox<>();
    private final TextField tagFilterField = new TextField();
    private final TextField posFilterField = new TextField();
    private final Tab tab;

    /** {@code clock} decides which words are due and how strong their memory is. */
    public WordListView(ViewContext context, WordRepository wordRepository, WordValidationService validationService,
                        Clock clock) {
        this.context = context;
        this.wordRepository = wordRepository;
        this.clock = clock;
        this.editDialog = new WordEditDialog(context, wordRepository, validationService);
        this.tab = Widgets.tab("wordListTab", "Word List", createContent());
        context.changes().subscribe(changes -> {
            if (changes.contains(DataChange.WORDS) || changes.contains(DataChange.REVIEWS)) {
                refresh();
            }
        });
        context.decks().onSwitch(deck -> refresh());
    }

    public Tab tab() {
        return tab;
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
        searchDebounce.setOnFinished(event -> refresh());
        searchField.textProperty().addListener((observable, oldValue, newValue) -> searchDebounce.playFromStart());
        tagFilterField.textProperty().addListener((observable, oldValue, newValue) -> searchDebounce.playFromStart());
        posFilterField.textProperty().addListener((observable, oldValue, newValue) -> searchDebounce.playFromStart());
        wordStatusFilter.valueProperty().addListener((observable, oldValue, newValue) -> refresh());
        Button refreshButton = new Button("Refresh");
        refreshButton.setId("refreshWordsButton");
        refreshButton.setOnAction(event -> refresh());
        Button editButton = new Button("Edit selected");
        editButton.setId("editWordButton");
        editButton.setOnAction(event -> editSelectedWord());
        Button deleteButton = new Button("Delete selected");
        deleteButton.setId("deleteWordButton");
        deleteButton.setOnAction(event -> deleteSelectedWord());

        HBox controls = new HBox(10, searchField, wordStatusFilter, tagFilterField, posFilterField,
            refreshButton, editButton, deleteButton);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        wordTable.setId("wordTable");
        wordTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        TableColumn<WordCard, String> englishCol = new TableColumn<>("English");
        englishCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getEnglish()));
        TableColumn<WordCard, String> chineseCol = new TableColumn<>("Chinese");
        chineseCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getChinese()));
        TableColumn<WordCard, String> nextCol = new TableColumn<>("Next review");
        nextCol.setCellValueFactory(data -> new SimpleStringProperty(DateTimeUtil.toDisplay(data.getValue().getNextReviewAt())));
        TableColumn<WordCard, String> intervalCol = new TableColumn<>("Interval");
        intervalCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getIntervalDays() + " days"));
        TableColumn<WordCard, String> strengthCol = new TableColumn<>("Memory");
        strengthCol.setCellValueFactory(data -> new SimpleStringProperty(
            Formats.percent(data.getValue().calculateMemoryStrength(LocalDateTime.now(clock)))));
        TableColumn<WordCard, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(data -> new SimpleStringProperty(
            WordListFilter.statusOf(data.getValue(), LocalDateTime.now(clock))));
        wordTable.getColumns().addAll(List.of(englishCol, chineseCol, nextCol, intervalCol, strengthCol, statusCol));

        VBox content = new VBox(12, controls, wordTable);
        content.setPadding(new Insets(24));
        VBox.setVgrow(wordTable, Priority.ALWAYS);
        return content;
    }

    public void refresh() {
        try {
            List<WordCard> words = wordRepository.search(context.decks().currentId(), searchField.getText());
            WordListFilter filter = new WordListFilter(wordStatusFilter.getValue(), tagFilterField.getText(),
                posFilterField.getText());
            LocalDateTime now = LocalDateTime.now(clock);
            wordItems.setAll(words.stream().filter(word -> filter.matches(word, now)).toList());
        } catch (SQLException e) {
            context.errors().reportFailure("Refresh failed", e);
        }
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

    private void deleteSelectedWord() {
        WordCard selected = wordTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            context.errors().showInfo("Please select a word to delete.");
            return;
        }
        if (context.dialogs().confirm("Delete word", "Delete " + selected.getEnglish() + "?",
            "Related review logs will also be removed.")) {
            try {
                wordRepository.deleteById(selected.getId());
                context.changes().publish(DataChange.WORDS);
            } catch (SQLException | RuntimeException e) {
                context.errors().reportFailure("Delete failed", e);
            }
        }
    }
}
