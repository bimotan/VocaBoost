package com.vocabtrainer.ui.words;

import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.ReviewScheduler;
import com.vocabtrainer.service.WordExtrasService;
import com.vocabtrainer.service.WordValidationService;
import com.vocabtrainer.service.cloze.ClozeMaker;
import com.vocabtrainer.service.scheduling.StudyDay;
import com.vocabtrainer.ui.AudioPlayer;
import com.vocabtrainer.ui.CellValue;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.Formats;
import com.vocabtrainer.ui.LazyRefresh;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import com.vocabtrainer.ui.WordDetails;
import com.vocabtrainer.ui.WordDetailsCard;
import com.vocabtrainer.ui.WordExtrasController;
import com.vocabtrainer.util.DateTimeUtil;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tab;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
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
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.vocabtrainer.util.Messages.tr;

/**
 * The Word List tab: search and filter the current deck's words, suspended ones included, or every
 * active deck's ("All decks", which adds a Deck column); edit one, and suspend, unsuspend or delete
 * the selected ones. Deleting asks first, says that the words' review history goes with them and
 * offers to suspend them instead. Under the table a card shows the selected word's phonetic, part
 * of speech, example (the word in bold), note and tags, and the synonyms, antonyms and recording an
 * online dictionary gave for it. The table's menu button (top right) hides and shows columns.
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
    private final ReviewLogRepository reviewLogRepository;
    private final Clock clock;
    private final Supplier<StudyDay> studyDays;
    private final WordEditDialog editDialog;
    private final ClozeMaker examples;
    private final WordDetailsCard detailsCard = new WordDetailsCard("wordDetails");
    private final WordExtrasController extrasController;
    /** Every word read from the database; the table shows the ones that pass the filters, sorted. */
    private final ObservableList<WordCard> words = FXCollections.observableArrayList();
    private final FilteredList<WordCard> filteredWords = new FilteredList<>(words);
    private final SortedList<WordCard> sortedWords = new SortedList<>(filteredWords);
    private final TableView<WordCard> wordTable = new TableView<>(sortedWords);
    private final TableColumn<WordCard, String> deckCol = new TableColumn<>(tr("decks.column.deck"));
    private final TextField searchField = new TextField();
    private final ComboBox<String> wordStatusFilter = new ComboBox<>();
    private final TextField tagFilterField = new TextField();
    private final TextField posFilterField = new TextField();
    private final CheckBox allDecksToggle = new CheckBox(tr("words.allDecks"));
    private final Button suspendButton = new Button(tr("review.suspend"));
    private final Button unsuspendButton = new Button(tr("words.unsuspend"));
    private final Tab tab;
    private final LazyRefresh lazy;
    /** The cell values computed since the last refresh, by row; see {@link #computeOnce}. */
    private final List<Map<?, ?>> cellValueCaches = new ArrayList<>();
    /** The names of the active decks, for the Deck column. */
    private Map<Long, String> deckNames = Map.of();
    /**
     * Whether the list holds every active deck's words. Not read from the Deck column, which the
     * table's menu button can hide or show.
     */
    private boolean listedAllDecks;
    /** When the words were read: the time their status and memory are shown for. */
    private LocalDateTime listedAt = LocalDateTime.MIN;
    /** The end of the study day the words were read on, which decides which ones are due today. */
    private LocalDateTime listedDayEnd = LocalDateTime.MIN;

    /**
     * {@code clock} and {@code studyDays} (the scheduler's study day, read at every refresh) decide which
     * words are due today and how strong their memory is; {@code examples} finds the word in its example
     * sentence; {@code wordExtras} and {@code audioPlayer} give the selected word's synonyms and recording.
     */
    public WordListView(ViewContext context, WordRepository wordRepository, ReviewLogRepository reviewLogRepository,
                        WordValidationService validationService, Clock clock, Supplier<StudyDay> studyDays,
                        ClozeMaker examples, WordExtrasService wordExtras, AudioPlayer audioPlayer) {
        this.context = context;
        this.extrasController = new WordExtrasController(detailsCard, context, wordExtras, audioPlayer);
        this.examples = examples;
        this.wordRepository = wordRepository;
        this.reviewLogRepository = reviewLogRepository;
        this.clock = clock;
        this.studyDays = studyDays;
        this.editDialog = new WordEditDialog(context, wordRepository, validationService);
        this.tab = Widgets.tab("wordListTab", tr("words.tab"), createContent());
        this.lazy = new LazyRefresh(tab, this::refresh, context.errors(), tr("common.refreshFailed"), false);
        context.changes().subscribe(changes -> {
            if (changes.contains(DataChange.WORDS) || changes.contains(DataChange.REVIEWS)
                || changes.contains(DataChange.REVIEW_SETTINGS) || changes.contains(DataChange.DECKS)) {
                lazy.markStale();
            }
            if (changes.contains(DataChange.SETTINGS)) {
                // Offline mode may have changed.
                extrasController.settingsChanged();
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
        searchField.setPromptText(tr("words.search"));
        searchField.setAccessibleText(tr("words.search"));
        wordStatusFilter.setId("wordStatusFilter");
        wordStatusFilter.getItems().setAll(WordListFilter.STATUSES);
        wordStatusFilter.setCellFactory(list -> statusCell());
        wordStatusFilter.setButtonCell(statusCell());
        wordStatusFilter.getSelectionModel().select("All");
        wordStatusFilter.setAccessibleText(tr("words.status.accessible"));
        tagFilterField.setId("wordTagFilterField");
        tagFilterField.setPromptText(tr("words.tagFilter"));
        tagFilterField.setAccessibleText(tr("words.tagFilter.accessible"));
        tagFilterField.setPrefWidth(120);
        posFilterField.setId("wordPosFilterField");
        posFilterField.setPromptText(tr("import.mapping.column.pos"));
        posFilterField.setAccessibleText(tr("words.posFilter.accessible"));
        posFilterField.setPrefWidth(120);
        allDecksToggle.setId("wordAllDecksToggle");
        allDecksToggle.setTooltip(new Tooltip(tr("words.allDecks.tooltip")));
        searchField.textProperty().addListener((observable, oldValue, newValue) -> applyFilters());
        tagFilterField.textProperty().addListener((observable, oldValue, newValue) -> applyFilters());
        posFilterField.textProperty().addListener((observable, oldValue, newValue) -> applyFilters());
        wordStatusFilter.valueProperty().addListener((observable, oldValue, newValue) -> applyFilters());
        allDecksToggle.selectedProperty().addListener((observable, wasSelected, selected) ->
            context.errors().guard(tr("common.refreshFailed"), lazy::refreshNow));
        Button refreshButton = new Button(tr("common.refresh"));
        refreshButton.setId("refreshWordsButton");
        refreshButton.setOnAction(event -> context.errors().guard(tr("common.refreshFailed"), lazy::refreshNow));
        Button editButton = new Button(tr("words.edit"));
        editButton.setId("editWordButton");
        editButton.setOnAction(event -> editSelectedWord());
        Button deleteButton = new Button(tr("words.delete"));
        deleteButton.setId("deleteWordButton");
        deleteButton.setOnAction(event -> deleteSelectedWords());
        suspendButton.setId("suspendWordsButton");
        suspendButton.setTooltip(new Tooltip(tr("words.suspend.tooltip")));
        suspendButton.setOnAction(event -> setSelectedSuspended(true));
        unsuspendButton.setId("unsuspendWordsButton");
        unsuspendButton.setTooltip(new Tooltip(tr("words.unsuspend.tooltip")));
        unsuspendButton.setOnAction(event -> setSelectedSuspended(false));

        HBox filters = new HBox(10, searchField, wordStatusFilter, tagFilterField, posFilterField, allDecksToggle,
            refreshButton);
        filters.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        // In a narrow window the search box gets narrower; the other controls keep their size.
        searchField.setMinWidth(150);
        for (Region region : List.of(wordStatusFilter, tagFilterField, posFilterField, allDecksToggle, refreshButton,
            editButton, suspendButton, unsuspendButton, deleteButton)) {
            region.setMinWidth(Region.USE_PREF_SIZE);
        }
        Label selectionHint = Widgets.styled(new Label(tr("words.selectionHint")), "muted-text");
        HBox actions = new HBox(10, editButton, suspendButton, unsuspendButton, deleteButton, selectionHint);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox controls = new VBox(8, filters, actions);

        wordTable.setId("wordTable");
        wordTable.setAccessibleText(tr("words.table.accessible"));
        wordTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        // Several words can be selected (Shift or Ctrl) to suspend, unsuspend or delete them together.
        wordTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        wordTable.getSelectionModel().getSelectedItems().addListener(
            (ListChangeListener<WordCard>) change -> updateSelectionActions());
        updateSelectionActions();
        // The table sorts the filtered words; with no sort column they keep the database's order (by English).
        sortedWords.comparatorProperty().bind(wordTable.comparatorProperty());
        // Sorting asks for a row's cell values many times, so they are computed once per word and refresh,
        // and text is compared by collation keys computed once per text.
        TableColumn<WordCard, String> englishCol = new TableColumn<>(tr("import.mapping.column.english"));
        englishCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getEnglish()));
        englishCol.setComparator(collated());
        TableColumn<WordCard, String> chineseCol = new TableColumn<>(tr("import.mapping.column.chinese"));
        chineseCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getChinese()));
        chineseCol.setComparator(collated());
        TableColumn<WordCard, String> phoneticCol = new TableColumn<>(tr("word.phonetic"));
        phoneticCol.setCellValueFactory(data -> new SimpleStringProperty(clean(data.getValue().getPhonetic())));
        phoneticCol.setComparator(collated());
        TableColumn<WordCard, String> posCol = new TableColumn<>(tr("import.mapping.column.pos"));
        posCol.setCellValueFactory(data -> new SimpleStringProperty(clean(data.getValue().getPartOfSpeech())));
        posCol.setComparator(collated());
        TableColumn<WordCard, CellValue<LocalDateTime>> nextCol = new TableColumn<>(tr("words.column.next"));
        computeOnce(nextCol, word -> new CellValue<>(word.getNextReviewAt(),
            DateTimeUtil.toDisplay(word.getNextReviewAt())));
        TableColumn<WordCard, CellValue<Integer>> intervalCol = new TableColumn<>(tr("words.column.interval"));
        computeOnce(intervalCol, WordListView::interval);
        // The chance of recalling the word now (FSRS retrievability); "New" before its first review.
        TableColumn<WordCard, CellValue<Double>> strengthCol = new TableColumn<>(tr("words.column.memory"));
        computeOnce(strengthCol, word -> memory(word, listedAt));
        TableColumn<WordCard, String> statusCol = new TableColumn<>(tr("import.mapping.column.status"));
        statusCol.setComparator(collated());
        computeOnce(statusCol, word -> WordListFilter.statusOf(word, listedAt, listedDayEnd));
        deckCol.setCellValueFactory(data ->
            new SimpleStringProperty(deckNames.getOrDefault(data.getValue().getDeckId(), "")));
        deckCol.setComparator(collated());
        deckCol.setVisible(false);
        wordTable.getColumns().addAll(List.of(englishCol, chineseCol, nextCol, intervalCol, strengthCol, statusCol,
            phoneticCol, posCol, deckCol));
        // Columns can be hidden and shown again from the menu button at the top right of the table.
        wordTable.setTableMenuButtonVisible(true);

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

    private static String clean(String value) {
        return value == null ? "" : value.strip();
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
            : new CellValue<>(null, tr("cardState.new"));
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
            Set<Long> selectedIds = selectedIds();
            deckNames = names;
            listedAllDecks = allDecks;
            deckCol.setVisible(allDecks);
            listedAt = LocalDateTime.now(clock);
            listedDayEnd = studyDays.get().end(listedAt);
            cellValueCaches.forEach(Map::clear);
            words.setAll(loaded);
            applyFilters();
            // Keep the selected words selected, with their details as they are now (e.g. after an edit).
            reselect(selected, selectedIds, true);
        } catch (SQLException e) {
            context.errors().reportFailure(tr("common.refreshFailed"), e);
        }
    }

    private static ListCell<String> statusCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                setText(empty || status == null ? null : WordListFilter.statusLabel(status));
            }
        };
    }

    /** Shows the words that match the search box and the filters; the database is not read again. */
    private void applyFilters() {
        WordCard selected = wordTable.getSelectionModel().getSelectedItem();
        Set<Long> selectedIds = selectedIds();
        WordListFilter filter = new WordListFilter(wordStatusFilter.getValue(), tagFilterField.getText(),
            posFilterField.getText());
        LocalDateTime now = listedAt;
        LocalDateTime dayEnd = listedDayEnd;
        Predicate<WordCard> search = WordListFilter.searching(searchField.getText());
        filteredWords.setPredicate(word -> search.test(word) && filter.matches(word, now, dayEnd));
        reselect(selected, selectedIds, false);
    }

    /**
     * Selects the rows of {@code selectedIds} again, by id, those still listed, with {@code shown} (the
     * word whose details were shown) selected last so its details still are. All rows at once:
     * selecting thousands of rows one by one (after Ctrl+A) takes seconds. When {@code shown} is no
     * longer listed, the details card is cleared only if {@code clearDetails}, e.g. after a refresh
     * removed the word.
     */
    private void reselect(WordCard shown, Set<Long> selectedIds, boolean clearDetails) {
        if (shown == null && selectedIds.isEmpty()) {
            updateSelectionActions();
            return;
        }
        int shownIndex = -1;
        List<Integer> others = new ArrayList<>();
        for (int index = 0; index < sortedWords.size(); index++) {
            long id = sortedWords.get(index).getId();
            if (shown != null && id == shown.getId()) {
                shownIndex = index;
            } else if (selectedIds.contains(id)) {
                others.add(index);
            }
        }
        wordTable.getSelectionModel().clearSelection();
        if (!others.isEmpty()) {
            wordTable.getSelectionModel().selectIndices(others.get(0),
                others.subList(1, others.size()).stream().mapToInt(Integer::intValue).toArray());
        }
        if (shownIndex >= 0) {
            wordTable.getSelectionModel().select(shownIndex);
            showDetails(sortedWords.get(shownIndex));
        } else if (clearDetails || wordTable.getSelectionModel().getSelectedItem() != null) {
            showDetails(wordTable.getSelectionModel().getSelectedItem());
        }
        updateSelectionActions();
    }

    private void showDetails(WordCard word) {
        extrasController.show(word == null ? null : word.getEnglish());
        if (word == null) {
            detailsCard.showMessage(tr("words.details.select"));
            return;
        }
        String deck = listedAllDecks ? "   (" + deckNames.getOrDefault(word.getDeckId(), "") + ")" : "";
        detailsCard.show(word.getEnglish() + "   " + word.getChinese() + deck,
            WordDetails.of(word, examples.highlight(word.getExampleSentence(), word.getEnglish())),
            tr("words.details.empty"));
    }

    private void editSelectedWord() {
        WordCard selected = wordTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            context.errors().showInfo(tr("words.edit.select"));
            return;
        }
        if (editDialog.edit(selected)) {
            context.errors().guard(tr("words.edit.refreshFailed"),
                () -> context.changes().publish(DataChange.WORDS));
        }
    }

    private List<WordCard> selectedWords() {
        return List.copyOf(wordTable.getSelectionModel().getSelectedItems());
    }

    private Set<Long> selectedIds() {
        return selectedWords().stream().map(WordCard::getId).collect(Collectors.toSet());
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
            context.errors().showInfo(suspended ? tr("words.suspend.select") : tr("words.unsuspend.select"));
            return;
        }
        try {
            wordRepository.setSuspended(ids, suspended);
            context.changes().publish(DataChange.WORDS);
        } catch (SQLException | RuntimeException e) {
            context.errors().reportFailure(suspended ? tr("review.suspend.failed") : tr("words.unsuspend.failed"), e);
        }
    }

    /**
     * Deletes the selected words after a confirmation that says their review history goes with them
     * and that only a backup brings them back, and that offers to suspend them instead. With "All
     * decks" the same word can be listed once per deck, so the confirmation says which deck's goes.
     */
    private void deleteSelectedWords() {
        List<WordCard> selected = selectedWords();
        if (selected.isEmpty()) {
            context.errors().showInfo(tr("words.delete.select"));
            return;
        }
        List<Long> ids = selected.stream().map(WordCard::getId).toList();
        try {
            int reviews = reviewLogRepository.countByWords(ids);
            boolean canSuspend = selected.stream().anyMatch(word -> !word.isSuspended());
            ButtonType delete = new ButtonType(tr("words.delete.button"), ButtonBar.ButtonData.OK_DONE);
            ButtonType suspendInstead = new ButtonType(tr("words.delete.suspendInstead"), ButtonBar.ButtonData.OTHER);
            ButtonType cancel = new ButtonType(tr("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
            ButtonType[] buttons = canSuspend
                ? new ButtonType[] {delete, suspendInstead, cancel}
                : new ButtonType[] {delete, cancel};
            Optional<ButtonType> choice = context.dialogs().choose(selected.size() == 1 ? tr("words.delete.title.one")
                    : tr("words.delete.title.many"),
                deleteHeader(selected), deleteWarning(selected.size(), reviews, canSuspend), buttons);
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
            context.errors().reportFailure(tr("words.delete.failed"), e);
        }
    }

    /** "Delete "lucid"?" or "Delete 3 words?", naming the deck (or the number of decks) with "All decks". */
    private String deleteHeader(List<WordCard> selected) {
        boolean one = selected.size() == 1;
        Object what = one ? selected.get(0).getEnglish() : selected.size();
        if (!listedAllDecks) {
            return one ? tr("words.delete.header.one", what) : tr("words.delete.header.many", what);
        }
        Set<Long> decks = selected.stream().map(WordCard::getDeckId).collect(Collectors.toSet());
        if (decks.size() > 1) {
            return tr("words.delete.header.manyFromDecks", what, decks.size());
        }
        String deck = deckNames.getOrDefault(selected.get(0).getDeckId(), tr("words.delete.itsDeck"));
        return one ? tr("words.delete.header.oneFromDeck", what, deck) : tr("words.delete.header.manyFromDeck", what, deck);
    }

    /**
     * What deleting {@code words} words with {@code reviews} review logs between them loses, that only
     * a backup brings it back, and, when {@code canSuspend}, that suspending keeps it.
     */
    static String deleteWarning(int words, int reviews, boolean canSuspend) {
        boolean one = words == 1;
        String history = reviews == 0
            ? (one ? tr("words.delete.noHistory.one") : tr("words.delete.noHistory.many"))
            : (one ? tr("words.delete.history.one", reviews) : tr("words.delete.history.many", reviews));
        String paragraph = System.lineSeparator() + System.lineSeparator();
        String text = history + paragraph
            + (one ? tr("words.delete.permanent.one") : tr("words.delete.permanent.many"));
        if (canSuspend) {
            text += paragraph + (one ? tr("words.delete.suspendHint.one") : tr("words.delete.suspendHint.many"));
        }
        return text;
    }
}
