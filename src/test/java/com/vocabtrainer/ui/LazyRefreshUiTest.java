package com.vocabtrainer.ui;

import com.vocabtrainer.domain.WordCard;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Hidden tabs do no work when data changes; they catch up when they are shown (review finding C8). */
@Tag("ui")
class LazyRefreshUiTest extends MainWindowUiTest {
    @Test
    void statisticsAreNotRecomputedWhileTheirTabIsHidden() throws Exception {
        review(true, "rateGoodButton");
        selectTab("statisticsTab");
        assertEquals(1, chartPoints("reviewCountChart").get(6).y());
        String hardestWords = text("hardestWordsArea");

        review(false, "rateAgainButton");
        review(true, "rateGoodButton");

        // Rating on the Review tab left the hidden Statistics tab alone...
        assertEquals(1, chartPoints("reviewCountChart").get(6).y());
        assertEquals(hardestWords, text("hardestWordsArea"));
        // ...and it catches up as soon as it is shown again.
        selectTab("statisticsTab");
        assertEquals(3, chartPoints("reviewCountChart").get(6).y());
        assertEquals(3, text("hardestWordsArea").lines().count());
    }

    @Test
    void ratingLeavesTheOtherTabsAloneUntilTheyAreShown() throws Exception {
        selectTab("wordListTab");
        selectTab("decksTab");
        selectTab("dashboardTab");

        WordCard reviewed = review(true, "rateGoodButton");

        assertEquals("0 / 20", text("reviewedTodayLabel"));
        assertEquals("Due", cell("wordTable", reviewed.getEnglish(), 5));
        assertEquals(String.valueOf(STARTER_WORDS), cell("deckTable", STARTER_DECK, 2));

        selectTab("dashboardTab");
        assertEquals("1 / 20", text("reviewedTodayLabel"));
        assertEquals(String.valueOf(STARTER_WORDS - 1), text("dueTodayLabel"));
        selectTab("wordListTab");
        assertEquals("Learning", cell("wordTable", reviewed.getEnglish(), 5));
        selectTab("decksTab");
        assertEquals(String.valueOf(STARTER_WORDS - 1), cell("deckTable", STARTER_DECK, 2));
    }

    @Test
    void theTabOnScreenRefreshesRightAway() {
        selectTab("decksTab");
        dialogs.answerText("TOEFL");

        click("newDeckButton");

        assertEquals("TOEFL", cell("deckTable", "TOEFL", 0));
        assertEquals("0", cell("deckTable", "TOEFL", 1));
    }

    /** The text in column {@code column} of the row whose first column shows {@code firstColumn}. */
    private String cell(String tableId, String firstColumn, int column) {
        return Fx.call(() -> {
            TableView<Object> table = table(tableId);
            TableColumn<Object, ?> key = table.getColumns().get(0);
            Object row = table.getItems().stream()
                .filter(item -> firstColumn.equals(String.valueOf(key.getCellData(item))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No row '" + firstColumn + "' in #" + tableId));
            return String.valueOf(table.getColumns().get(column).getCellData(row));
        });
    }
}
