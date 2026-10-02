package com.vocabtrainer.ui.words;

import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.ReviewScheduler;
import com.vocabtrainer.service.WordValidationService;
import com.vocabtrainer.service.cloze.ClozeMaker;
import com.vocabtrainer.service.scheduling.StudyDay;
import com.vocabtrainer.ui.CellValue;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.Formats;
import com.vocabtrainer.ui.LazyRefresh;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import com.vocabtrainer.ui.WordDetails;
import com.vocabtrainer.ui.WordDetailsCard;
import com.vocabtrainer.util.DateTimeUtil;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Tab;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.sql.SQLException;
import java.text.CollationKey;
import java.text.Collator;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The Word List tab: search and filter the current deck's words, or every active deck's ("All
 * decks", which adds a Deck column), and edit or delete one. Under the table a card shows the
 * selected word's phonetic, part of speech, example (the word in bold), note and tags.
 *
 * <p>The words are read from the database when the tab is refreshed (a data change, a deck switch,
 * "All decks" or Refresh); the search box and the filters only filter those rows in memory, and the
 * table sorts them, so typing does not read the database and a list of 10,000 words stays quick.
 * Numeric and date columns sort by value. The status, memory and Due filter are those of the time
 * the list was read.
 */
public final class WordListView {
    private final ViewContext context;
    private final WordRepository wordRepository;
    private final Clock clock;
    private final Supplier<StudyDay> studyDays;
    private final WordEditDialog editDialog;
    private final ClozeMaker examples;
    private final WordDetailsCard detailsCard = new WordDetailsCard("wordDetails");
    /** Every word read from the database; the table shows the ones that pass the filters, sorted. */
    private final ObservableList<WordCard> words = FXCollections.observableArrayList();
    private final FilteredList<WordCard> filteredWords = new FilteredList<>(words);
    private final SortedList<WordCard> sortedWords = new SortedList<>(filteredWords);
    private final TableView<WordCard> wordTable = new TableView<>(sortedWords);
    private final TableColumn<WordCard, String> deckCol = new TableColumn<>("Deck");
    private final TextField searchField = new TextField();
    private final ComboBox<String> wordStatusFilter = new ComboBox<>();
    private final TextField tagFilterField = new TextField();
    private final TextField posFilterField = new TextField();
    private final CheckBox allDecksToggle = new CheckBox("All decks");
    private final Tab tab;
    private final LazyRefresh lazy;
    /** The cell values computed since the last refresh, by row; see {@link #computeOnce}. */
    private final List<Map<?, ?>> cellValueCaches = new ArrayList<>();
    /** The names of the active decks, for the Deck column. */
    private Map<Long, String> deckNames = Map.of();
    /** When the words were read: the time their status and memory are shown for. */
    private LocalDateTime listedAt = LocalDateTime.MIN;
    /** The end of the study day the words were read on, which decides which ones are due today. */
    private LocalDateTime listedDayEnd = LocalDateTime.MIN;

    /**
     * {@code clock} and {@code studyDays} (the scheduler's study day, read at every refresh) decide which
     * words are due today and how strong their memory is; {@code examples} finds the word in its example
     * sentence.
     */
    public WordListView(ViewContext context, WordRepository wordRepository, WordValidationService validationService,
                        Clock clock, Supplier<StudyDay> studyDays, ClozeMaker examples) {
        this.context = context;
        this.examples = examples;
        this.wordRepository = wordRepository;
        this.clock = clock;
        this.studyDays = studyDays;
        this.editDialog = new WordEditDialog(context, wordRepository, validationService);
        this.tab = Widgets.tab("wordListTab", "Word List", createContent());
        this.lazy = new LazyRefresh(tab, this::refresh, context.errors(), "Refresh failed", false);
        context.changes().subscribe(changes -> {
            if (changes.contains(DataChange.WORDS) || changes.contains(DataChange.REVIEWS)
                || changes.contains(DataChange.REVIEW_SETTINGS) || changes.contains(DataChange.DECKS)) {
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
        allDecksToggle.setId("wordAllDecksToggle");
        allDecksToggle.setTooltip(new Tooltip("Search the words of every active deck; the Deck column says"
            + " which deck each one is in"));
        searchField.textProperty().addListener((observable, oldValue, newValue) -> applyFilters());
        tagFilterField.textProperty().addListener((observable, oldValue, newValue) -> applyFilters());
        posFilterField.textProperty().addListener((observable, oldValue, newValue) -> applyFilters());
        wordStatusFilter.valueProperty().addListener((observable, oldValue, newValue) -> applyFilters());
        allDecksToggle.selectedProperty().addListener((observable, wasSelected, selected) ->
            context.errors().guard("Refresh failed", lazy::refreshNow));
        Button refreshButton = new Button("Refresh");
        refreshButton.setId("refreshWordsButton");
        refreshButton.setOnAction(event -> context.errors().guard("Refresh failed", lazy::refreshNow));
        Button editButton = new Button("Edit selected");
        editButton.setId("editWordButton");
        editButton.setOnAction(event -> editSelectedWord());
        Button deleteButton = new Button("Delete selected");
        deleteButton.setId("deleteWordButton");
        deleteButton.setOnAction(event -> deleteSelectedWord());

        HBox controls = new HBox(10, searchField, wordStatusFilter, tagFilterField, posFilterField, allDecksToggle,
            refreshButton, editButton, deleteButton);
        controls.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        wordTable.setId("wordTable");
        wordTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        // The table sorts the filtered words; with no sort column they keep the database's order (by English).
        sortedWords.comparatorProperty().bind(wordTable.comparatorProperty());
        // Sorting asks for a row's cell values many times, so they are computed once per word and refresh,
        // and text is compared by collation keys computed once per text.
        TableColumn<WordCard, String> englishCol = new TableColumn<>("English");
        englishCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getEnglish()));
        englishCol.setComparator(collated());
        TableColumn<WordCard, String> chineseCol = new TableColumn<>("Chinese");
        chineseCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getChinese()));
        chineseCol.setComparator(collated());
        TableColumn<WordCard, CellValue<LocalDateTime>> nextCol = new TableColumn<>("Next review");
        computeOnce(nextCol, word -> new CellValue<>(word.getNextReviewAt(),
            DateTimeUtil.toDisplay(word.getNextReviewAt())));
        TableColumn<WordCard, CellValue<Integer>> intervalCol = new TableColumn<>("Interval");
        computeOnce(intervalCol, WordListView::interval);
        // The chance of recalling the word now (FSRS retrievability); "New" before its first review.
        TableColumn<WordCard, CellValue<Double>> strengthCol = new TableColumn<>("Memory");
        computeOnce(strengthCol, word -> memory(word, listedAt));
        TableColumn<WordCard, String> statusCol = new TableColumn<>("Status");
        statusCol.setComparator(collated());
        computeOnce(statusCol, word -> WordListFilter.statusOf(word, listedAt, listedDayEnd));
        deckCol.setCellValueFactory(data ->
            new SimpleStringProperty(deckNames.getOrDefault(data.getValue().getDeckId(), "")));
        deckCol.setComparator(collated());
        deckCol.setVisible(false);
        wordTable.getColumns().addAll(List.of(englishCol, chineseCol, nextCol, intervalCol, strengthCol, statusCol,
            deckCol));

        wordTable.getSelectionModel().selectedItemProperty().addListener(
            (observable, oldWord, word) -> showDetails(word));
        showDetails(null);

        VBox content = new VBox(12, controls, wordTable, detailsCard.root());
        content.setPadding(new Insets(24));
        VBox.setVgrow(wordTable, Priority.ALWAYS);
        return content;
    }

    /**
     * Compares texts in the order of the user's language, as the table does by default, with each
     * text's collation key computed once until the next refresh.
     */
    private Comparator<String> collated() {
        Collator collator = Collator.getInstance();
        Map<String, CollationKey> keys = new HashMap<>();
        cellValueCaches.add(keys);
        Function<String, CollationKey> key = text -> keys.computeIfAbsent(text == null ? "" : text,
            collator::getCollationKey);
        return (left, right) -> key.apply(left).compareTo(key.apply(right));
    }

    /** Shows {@code value} of each row in {@code column}, computed once per word until the next refresh. */
    private <V> void computeOnce(TableColumn<WordCard, V> column, Function<WordCard, V> value) {
        Map<WordCard, V> computed = new IdentityHashMap<>();
        cellValueCaches.add(computed);
        column.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(computed.computeIfAbsent(data.getValue(), value)));
    }

    /** New words first ("-"), then the words in their learning steps, then by review interval. */
    private static CellValue<Integer> interval(WordCard word) {
        CardState state = word.getState();
        Integer days = state == CardState.NEW ? null : state.isLearning() ? 0 : word.getIntervalDays();
        return new CellValue<>(days, Formats.cardInterval(word));
    }

    /** The chance of recalling the word at {@code now}; a new word has none and sorts first. */
    private static CellValue<Double> memory(WordCard word, LocalDateTime now) {
        OptionalDouble recall = ReviewScheduler.retrievability(word, now);
        return recall.isPresent()
            ? new CellValue<>(recall.getAsDouble(), Formats.percent(recall.getAsDouble()))
            : new CellValue<>(null, "New");
    }

    /** Reads the words of the current deck, or of every active deck, from the database. */
    private void refresh() {
        try {
            List<Deck> decks = List.copyOf(context.decks().activeDecks());
            Map<Long, String> names = new HashMap<>();
            decks.forEach(deck -> names.put(deck.getId(), deck.getName()));
            boolean allDecks = allDecksToggle.isSelected();
            List<WordCard> loaded = new ArrayList<>();
            // A blank search is every word the list shows.
            if (allDecks) {
                for (Deck deck : decks) {
                    loaded.addAll(wordRepository.search(deck.getId(), ""));
                }
                // By English word like one deck's list, a word's rows in the deck selector's order.
                loaded.sort(Comparator.comparing(word -> word.getEnglish().toLowerCase(Locale.ROOT)));
            } else {
                loaded.addAll(wordRepository.search(context.decks().currentId(), ""));
            }
            WordCard selected = wordTable.getSelectionModel().getSelectedItem();
            deckNames = names;
            deckCol.setVisible(allDecks);
            listedAt = LocalDateTime.now(clock);
            listedDayEnd = studyDays.get().end(listedAt);
            cellValueCaches.forEach(Map::clear);
            words.setAll(loaded);
            applyFilters();
            // Keep the selected word selected, with its details as they are now (e.g. after an edit).
            reselect(selected, true);
        } catch (SQLException e) {
            context.errors().reportFailure("Refresh failed", e);
        }
    }

    /** Shows the words that match the search box and the filters; the database is not read again. */
    private void applyFilters() {
        WordCard selected = wordTable.getSelectionModel().getSelectedItem();
        WordListFilter filter = new WordListFilter(wordStatusFilter.getValue(), tagFilterField.getText(),
            posFilterField.getText());
        LocalDateTime now = listedAt;
        LocalDateTime dayEnd = listedDayEnd;
        Predicate<WordCard> search = WordListFilter.searching(searchField.getText());
        filteredWords.setPredicate(word -> search.test(word) && filter.matches(word, now, dayEnd));
        reselect(selected, false);
    }

    /**
     * Selects the row of {@code word} again, by id, if it is still listed. When it is not, the details
     * card is cleared only if {@code clearDetails}, e.g. after a refresh removed the word.
     */
    private void reselect(WordCard word, boolean clearDetails) {
        if (word == null) {
            return;
        }
        sortedWords.stream()
            .filter(listed -> listed.getId() == word.getId())
            .findFirst()
            .ifPresentOrElse(listed -> {
                wordTable.getSelectionModel().select(listed);
                showDetails(listed);
            }, () -> {
                if (clearDetails) {
                    showDetails(null);
                }
            });
    }

    private void showDetails(WordCard word) {
        if (word == null) {
            detailsCard.showMessage("Select a word to see its phonetic, part of speech, example, note and tags.");
            return;
        }
        String deck = deckCol.isVisible() ? "   (" + deckNames.getOrDefault(word.getDeckId(), "") + ")" : "";
        detailsCard.show(word.getEnglish() + "   " + word.getChinese() + deck,
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
            context.errors().guard("Word saved, but refreshing the views failed",
                () -> context.changes().publish(DataChange.WORDS));
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
