package com.vocabtrainer.ui;

import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.WordRepository;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Word List and deck tables: numeric and date columns sort by value, and the Word List filters a
 * large deck in memory, without reading the database at every keystroke.
 */
@Tag("ui")
class WordTableUiTest extends MainWindowUiTest {
    /** Generous: filtering 10,000 rows takes a few milliseconds on a laptop. */
    private static final long KEYSTROKE_BOUND_MILLIS = 1_500;
    private static final int LARGE_DECK = 10_000;

    private final AtomicInteger wordListReads = new AtomicInteger();

    @Override
    AppServices.Builder configure(AppServices.Builder builder) {
        return builder.wordRepository(CountingWordRepository::new);
    }

    /** Counts the Word List's reads of a deck's words. */
    private final class CountingWordRepository extends WordRepository {
        CountingWordRepository(DatabaseManager databaseManager) {
            super(databaseManager);
        }

        @Override
        public List<WordCard> search(long deckId, String query) throws SQLException {
            wordListReads.incrementAndGet();
            return super.search(deckId, query);
        }
    }

    @Test
    void intervalMemoryAndNextReviewSortByValueNotAsText() throws Exception {
        dialogs.answerText("Sorting");
        click("newDeckButton");
        long deckId = currentDeck().getId();
        LocalDateTime now = LocalDateTime.now();
        // As text, "10 days" < "14 days" < "2 days" < "3 days" and "100%" < "45%" < "9%".
        services.wordRepository().insert(review(deckId, "alpha", 2, now.minusDays(1)));
        services.wordRepository().insert(review(deckId, "bravo", 10, now.minusDays(5000)));
        services.wordRepository().insert(review(deckId, "charlie", 14, now.minusDays(2)));
        services.wordRepository().insert(review(deckId, "delta", 3, now.minusDays(9)));
        services.wordRepository().insert(WordCard.createNew(deckId, "echo", "新词"));
        WordCard learning = review(deckId, "foxtrot", 1, now.minusMinutes(5));
        learning.setState(CardState.LEARNING);
        services.wordRepository().insert(learning);
        selectTab("wordListTab");
        click("refreshWordsButton");

        assertEquals(List.of("alpha", "bravo", "charlie", "delta", "echo", "foxtrot"), englishInTableOrder());
        assertEquals("10 days", cell("wordTable", "bravo", 3));

        sortBy("Interval", TableColumn.SortType.ASCENDING);
        // New ("-") first, then learning, then by days.
        assertEquals(List.of("echo", "foxtrot", "alpha", "delta", "bravo", "charlie"), englishInTableOrder());
        sortBy("Interval", TableColumn.SortType.DESCENDING);
        assertEquals(List.of("charlie", "bravo", "delta", "alpha", "foxtrot", "echo"), englishInTableOrder());

        sortBy("Memory", TableColumn.SortType.ASCENDING);
        List<String> byMemory = englishInTableOrder();
        assertEquals("echo", byMemory.get(0), "a new word has no memory yet");
        List<Double> recalls = new ArrayList<>();
        for (String english : byMemory.subList(1, byMemory.size())) {
            String percent = cell("wordTable", english, 4);
            recalls.add(Double.parseDouble(percent.replace("%", "")));
        }
        for (int index = 1; index < recalls.size(); index++) {
            assertTrue(recalls.get(index - 1) <= recalls.get(index), "memory ascending: " + byMemory + " " + recalls);
        }
        assertTrue(recalls.get(0) < 10 && recalls.get(recalls.size() - 1) == 100, recalls.toString());

        sortBy("Next review", TableColumn.SortType.ASCENDING);
        List<String> byNextReview = englishInTableOrder();
        List<LocalDateTime> due = new ArrayList<>();
        for (String english : byNextReview) {
            due.add(services.wordRepository().findByEnglish(deckId, english).orElseThrow().getNextReviewAt());
        }
        List<LocalDateTime> sorted = new ArrayList<>(due);
        sorted.sort(null);
        assertEquals(sorted, due, "next review ascending: " + byNextReview);
    }

    @Test
    void theDeckTableSortsWordAndDueCountsByValue() throws Exception {
        for (String name : List.of("Two", "Ten")) {
            dialogs.answerText(name);
            click("newDeckButton");
            int count = name.equals("Two") ? 2 : 10;
            for (int index = 0; index < count; index++) {
                services.wordRepository().insert(WordCard.createNew(currentDeck().getId(), name + index, "词"));
            }
        }
        selectTab("decksTab");
        click("refreshDecksButton");

        // As text, "10" < "2" < "215".
        sortDecksBy("Words", TableColumn.SortType.ASCENDING);
        assertEquals(List.of("Two", "Ten", STARTER_DECK), deckNamesInTableOrder());
        sortDecksBy("Words", TableColumn.SortType.DESCENDING);
        assertEquals(List.of(STARTER_DECK, "Ten", "Two"), deckNamesInTableOrder());
        // Due: 2 and 10 new words, 20 of the starter deck's (the new-words limit).
        sortDecksBy("Due", TableColumn.SortType.ASCENDING);
        assertEquals(List.of("Two", "Ten", STARTER_DECK), deckNamesInTableOrder());
        assertEquals("10", cell("deckTable", "Ten", 1));
    }

    @Test
    void aTenThousandWordDeckIsFilteredInMemoryWithoutReadingTheDatabaseAtEveryKeystroke() throws Exception {
        dialogs.answerText("Large");
        click("newDeckButton");
        long deckId = currentDeck().getId();
        LocalDateTime now = LocalDateTime.now();
        List<WordCard> words = new ArrayList<>();
        for (int index = 0; index < LARGE_DECK; index++) {
            String english = String.format("word%05d", index);
            // Two thirds in review, with intervals from 1 to 365 days and reviews up to a year ago.
            WordCard word = index % 3 == 0 ? WordCard.createNew(deckId, english, "词" + index)
                : review(deckId, english, 1 + index % 365, now.minusDays(index % 400).minusMinutes(index));
            word.setTags(index % 100 == 0 ? "hundredth" : "generated");
            word.setPartOfSpeech(index % 2 == 0 ? "noun" : "verb");
            words.add(word);
        }
        services.wordRepository().insertAll(words);

        long loadStart = System.nanoTime();
        selectTab("wordListTab");
        long loadMillis = millisSince(loadStart);
        assertEquals(LARGE_DECK, rowCount("wordTable"));
        int readsBeforeTyping = wordListReads.get();

        List<Long> keystrokeMillis = new ArrayList<>();
        String query = "word099";
        for (int length = 1; length <= query.length(); length++) {
            long start = System.nanoTime();
            type("wordSearchField", query.substring(0, length));
            Fx.flush();
            keystrokeMillis.add(millisSince(start));
        }
        assertEquals(100, rowCount("wordTable"), "word09900 to word09999");
        long tagStart = System.nanoTime();
        type("wordSearchField", "");
        type("wordTagFilterField", "hundredth");
        Fx.flush();
        long tagMillis = millisSince(tagStart);
        assertEquals(LARGE_DECK / 100, rowCount("wordTable"));
        type("wordTagFilterField", "");
        assertEquals(LARGE_DECK, rowCount("wordTable"));

        List<Long> sortMillis = new ArrayList<>();
        for (String column : List.of("Memory", "Interval", "Next review", "Status", "English")) {
            long sortStart = System.nanoTime();
            sortBy(column, TableColumn.SortType.DESCENDING);
            Fx.flush();
            sortMillis.add(millisSince(sortStart));
        }
        assertEquals("word09999", englishInTableOrder().get(0));

        assertEquals(readsBeforeTyping, wordListReads.get(), "typing and sorting do not read the database");
        long slowest = keystrokeMillis.stream().mapToLong(Long::longValue).max().orElseThrow();
        System.out.printf("Word List with %d words: first load %d ms, keystrokes %s ms, tag filter %d ms,"
            + " sorts by Memory, Interval, Next review, Status and English %s ms%n", LARGE_DECK, loadMillis,
            keystrokeMillis, tagMillis, sortMillis);
        assertTrue(slowest < KEYSTROKE_BOUND_MILLIS, "keystrokes took " + keystrokeMillis + " ms");
    }

    private static WordCard review(long deckId, String english, int days, LocalDateTime lastReview) {
        WordCard word = WordCard.createNew(deckId, english, english + "的意思");
        word.setState(CardState.REVIEW);
        word.setStability(days);
        word.setDifficulty(5);
        word.setIntervalDays(days);
        word.setRepetitions(3);
        word.setConsecutiveCorrect(3);
        word.setLastReviewedAt(lastReview);
        word.setNextReviewAt(lastReview.plusDays(days));
        return word;
    }

    private void sortBy(String header, TableColumn.SortType type) {
        sort("wordTable", header, type);
    }

    private void sortDecksBy(String header, TableColumn.SortType type) {
        sort("deckTable", header, type);
    }

    private void sort(String tableId, String header, TableColumn.SortType type) {
        Fx.run(() -> {
            TableView<Object> table = table(tableId);
            TableColumn<Object, ?> column = table.getColumns().stream()
                .filter(candidate -> header.equals(candidate.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No column " + header + " in #" + tableId));
            column.setSortType(type);
            table.getSortOrder().setAll(List.of(column));
        });
    }

    private List<String> englishInTableOrder() {
        return Fx.call(() -> this.<WordCard>table("wordTable").getItems().stream().map(WordCard::getEnglish).toList());
    }

    private List<String> deckNamesInTableOrder() {
        return Fx.call(() -> {
            TableView<Object> table = table("deckTable");
            TableColumn<Object, ?> nameColumn = table.getColumns().get(0);
            return table.getItems().stream().map(row -> String.valueOf(nameColumn.getCellData(row))).toList();
        });
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
