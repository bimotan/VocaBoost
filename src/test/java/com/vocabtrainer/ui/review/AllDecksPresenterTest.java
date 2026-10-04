package com.vocabtrainer.ui.review;

import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.service.ReviewService;
import com.vocabtrainer.service.ReviewSettings;
import com.vocabtrainer.service.SettingsService;
import com.vocabtrainer.ui.DataChanges;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Review tab's "All decks" without JavaFX (review finding G6). */
class AllDecksPresenterTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private final ReviewSessionPresenterTest.ManualTasks tasks = new ReviewSessionPresenterTest.ManualTasks();
    private AppServices services;
    private ReviewSessionPresenter presenter;
    private Deck starter;
    private Deck toefl;
    private WordCard overdue;

    @BeforeEach
    void setUp() throws SQLException {
        services = AppServices.builder(tempDir.resolve("vocab.db")).open();
        databases.track(services.databaseManager());
        starter = services.startupDeck();
        toefl = services.deckService().createDeck("TOEFL");
        overdue = WordCard.createNew(toefl.getId(), "quagmire", "困境");
        overdue.setState(CardState.REVIEW);
        overdue.setStability(5);
        overdue.setDifficulty(5);
        overdue.setRepetitions(2);
        overdue.setIntervalDays(5);
        overdue.setLastReviewedAt(LocalDateTime.now().minusDays(30));
        overdue.setNextReviewAt(LocalDateTime.now().minusDays(25));
        overdue = services.wordRepository().insert(overdue);
        presenter = newPresenter();
        presenter.showDeck(starter.getId());
    }

    private ReviewSessionPresenter newPresenter() {
        ReviewSessionPresenter created = new ReviewSessionPresenter(services.reviewService(), services.goalService(),
            () -> new ReviewSessionPresenterTest.FakeAi(), tasks, new DataChanges(), (title, error) -> { });
        Map<Long, String> names = Map.of(starter.getId(), starter.getName(), toefl.getId(), toefl.getName());
        created.setDeckNames(id -> names.getOrDefault(id, ""));
        return created;
    }

    @Test
    void everyDecksCardsAreAskedAndEachNamesItsDeck() {
        assertFalse(presenter.isAllDecks());
        assertEquals(starter.getId(), presenter.card().orElseThrow().getDeckId(), "one deck by default");

        presenter.setAllDecks(true);

        WordCard card = presenter.card().orElseThrow();
        assertEquals(overdue.getId(), card.getId(), "the other deck's overdue review comes first");
        assertTrue(presenter.details().startsWith("English → Chinese | Deck: TOEFL | Review | "), presenter.details());
        assertFalse(presenter.canChangeNewCardsPerDay(), "each deck's own limit applies");

        presenter.setAnswer("困境");
        presenter.submit();
        presenter.rate(ReviewRating.GOOD);

        WordCard next = presenter.card().orElseThrow();
        assertEquals(starter.getId(), next.getDeckId());
        assertTrue(presenter.details().contains("| Deck: " + starter.getName() + " | New"), presenter.details());
        assertEquals(1, services.goalService().getTodayProgress(toefl.getId()).reviewedCount());
        assertEquals(0, services.goalService().getTodayProgress(starter.getId()).reviewedCount());
    }

    @Test
    void aDeckSwitchKeepsTheSessionAndAnArchivedDecksCardGoes() {
        presenter.setAllDecks(true);
        long shown = presenter.cardNumber();
        assertEquals(overdue.getId(), presenter.card().orElseThrow().getId());

        presenter.showDeck(toefl.getId());
        assertEquals(shown, presenter.cardNumber(), "switching the header's deck does not start a new session");
        assertEquals(overdue.getId(), presenter.card().orElseThrow().getId());

        services.deckService().archiveDeck(toefl.getId());
        presenter.decksChanged();

        assertEquals(starter.getId(), presenter.card().orElseThrow().getDeckId(), "the archived deck's card is replaced");
    }

    @Test
    void theChoiceIsRememberedAndCanBeTakenBack() {
        presenter.setAllDecks(true);
        assertTrue(newPresenter().isAllDecks(), "the next launch reviews every deck too");

        presenter.setAllDecks(false);
        assertEquals(starter.getId(), presenter.card().orElseThrow().getDeckId());
        assertTrue(presenter.canChangeNewCardsPerDay());
        assertFalse(newPresenter().isAllDecks());
    }

    @Test
    void aCompleteSessionAddsUpTodayOfEveryDeck() {
        ReviewSettings settings = new ReviewSettings(new SettingsService(services.settingsRepository()));
        settings.saveNewCardsPerDay(starter.getId(), 0);
        settings.saveNewCardsPerDay(toefl.getId(), 0);
        presenter.setAllDecks(true);
        presenter.setAnswer("困境");
        presenter.submit();
        presenter.rate(ReviewRating.GOOD);

        assertTrue(presenter.isComplete());
        assertTrue(presenter.completionMetrics().contains("Today in every deck: reviews 1/40 | New words 0/10"),
            presenter.completionMetrics());
        assertEquals("No due words right now; the decks with new words reached today's new words/day limits.",
            presenter.details());
        assertEquals(2, services.reviewService().sessionDecks(ReviewService.ALL_DECKS).size());
    }
}
