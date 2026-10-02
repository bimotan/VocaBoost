package com.vocabtrainer.ui;

import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.service.AiService;
import com.vocabtrainer.service.ExplanationRequest;
import com.vocabtrainer.service.MockAiService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When a rating makes a card a leech, the Review tab offers to suspend it and a memory aid (review finding A12). */
@Tag("ui")
class LeechNoticeUiTest extends MainWindowUiTest {
    private final List<ExplanationRequest> requests = new CopyOnWriteArrayList<>();

    @Override
    AppServices.Builder configure(AppServices.Builder builder) {
        if (testName().startsWith("withoutAProvider")) {
            return builder;
        }
        return builder.aiService((cache, settings) -> new AiService() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String explain(WordCard word) {
                return explain(ExplanationRequest.of(word));
            }

            @Override
            public String explain(ExplanationRequest request) {
                requests.add(request);
                return request.focus() == ExplanationRequest.Focus.MEMORY_AID
                    ? "Memory tip: cavil 像 \"carve hill\"：在小山上挑刺。"
                    : "Explanation of " + request.word().getEnglish();
            }
        });
    }

    @Test
    void theLeechNoticeOffersAMemoryAidAndSuspend() throws SQLException {
        WordCard leech = rateLeechAgain();
        assertTrue(isVisible("leechBox"));
        assertEquals("Leech: \"cavil\" lapsed 8 times. Suspend it for now, or find a way to remember it.",
            text("leechNoticeLabel"));
        assertTrue(isVisible("memoryAidButton"));
        assertFalse(isVisible("memoryAidLabel"));

        click("memoryAidButton");
        waitForBackgroundTasks();

        assertEquals("Memory aid for \"cavil\":" + System.lineSeparator()
            + "Memory tip: cavil 像 \"carve hill\"：在小山上挑刺。", text("memoryAidLabel"));
        assertTrue(isVisible("memoryAidLabel"));
        assertEquals(ExplanationRequest.Focus.MEMORY_AID, requests.get(requests.size() - 1).focus());
        snapshot("leech-notice");

        click("suspendLeechButton");

        assertTrue(services.wordRepository().findById(leech.getId()).orElseThrow().isSuspended());
        assertTrue(text("leechNoticeLabel").contains("Suspended"), text("leechNoticeLabel"));
        assertTrue(isDisabled("suspendLeechButton"));
        assertEquals("Review complete", text("reviewWordLabel"), "the leech was the deck's only card");
    }

    @Test
    void withoutAProviderTheNoticeOnlyOffersSuspend() throws SQLException {
        rateLeechAgain();

        assertTrue(isVisible("leechBox"));
        assertFalse(isVisible("memoryAidButton"));
        assertFalse(isDisabled("suspendLeechButton"));
        assertTrue(services.aiServices().get() instanceof MockAiService);
    }

    /** A deck with only "cavil", which lapsed 7 times, rated Again on the Review tab: the 8th lapse. */
    private WordCard rateLeechAgain() throws SQLException {
        dialogs.answerText("Leech");
        click("newDeckButton");
        WordCard card = WordCard.createNew(currentDeck().getId(), "cavil", "挑剔");
        card.setState(CardState.REVIEW);
        card.setStability(2);
        card.setDifficulty(9);
        card.setRepetitions(20);
        card.setLapses(WordCard.LEECH_LAPSES - 1);
        card.setLastReviewedAt(LocalDateTime.now().minusDays(3));
        card.setNextReviewAt(LocalDateTime.now().minusDays(1));
        WordCard leech = services.wordRepository().insert(card);
        selectTab("reviewTab");
        click("resetSessionButton");
        assertFalse(isVisible("leechBox"));
        review(false, "rateAgainButton");
        return leech;
    }
}
