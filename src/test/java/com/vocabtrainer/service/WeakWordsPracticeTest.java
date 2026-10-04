package com.vocabtrainer.service;

import com.vocabtrainer.TestClock;
import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.ReviewKind;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewOutcome;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.AchievementRepository;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.GoalRepository;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.SettingsRepository;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.scheduling.IntervalPreview;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Weak Words mode practices weak words that are not due without rescheduling them (review finding
 * A1): before, every practice grew the interval as if it were a due review, so a few minutes of
 * drilling pushed the hardest words months away, and the session never ended.
 */
class WeakWordsPracticeTest {
    /** Tuesday 10 March 2026, 9:00; the study day ends at 4:00 on the 11th. */
    private static final LocalDateTime START = LocalDateTime.of(2026, 3, 10, 9, 0);
    private static final LocalDateTime TOMORROW = LocalDateTime.of(2026, 3, 11, 4, 0);

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private final TestClock clock = new TestClock(START);
    private Deck deck;
    private WordRepository words;
    private ReviewLogRepository logs;
    private GoalService goals;
    private ReviewService service;

    @BeforeEach
    void setUp() throws SQLException {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("weak.db"));
        deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        words = new WordRepository(databaseManager);
        logs = new ReviewLogRepository(databaseManager);
        goals = new GoalService(new GoalRepository(databaseManager), logs, clock);
        AchievementService achievements = new AchievementService(new AchievementRepository(databaseManager), goals, clock);
        ReviewSettings settings = new ReviewSettings(new SettingsService(new SettingsRepository(databaseManager)));
        service = new ReviewService(words, logs, new SimilarityService(), new ReviewScheduler(), goals, achievements,
            clock, settings, new Random(7));
    }

    @Test
    void practicingAWeakWordThatIsNotDueLeavesItsScheduleAndMemoryAlone() throws SQLException {
        WordCard weak = words.insert(weakWord("abate", START.plusDays(4)));
        WordCard before = stored(weak);

        for (int i = 0; i < 6; i++) {
            service.startSession(deck.getId(), ReviewMode.WEAK_WORDS, 0);
            WordCard shown = service.nextWord(deck.getId(), ReviewMode.WEAK_WORDS).orElseThrow();
            assertEquals(weak.getId(), shown.getId());
            ReviewOutcome outcome = answer(shown, shown.getChinese(), i % 2 == 0 ? ReviewRating.EASY : ReviewRating.GOOD);
            assertTrue(outcome.isPractice());
            clock.advance(Duration.ofMinutes(3));
        }

        WordCard after = stored(weak);
        assertEquals(before.getNextReviewAt(), after.getNextReviewAt(), "the due date did not move");
        assertEquals(before.getStability(), after.getStability());
        assertEquals(before.getDifficulty(), after.getDifficulty());
        assertEquals(before.getState(), after.getState());
        assertEquals(before.getIntervalDays(), after.getIntervalDays());
        assertEquals(before.getRepetitions(), after.getRepetitions());
        assertEquals(before.getConsecutiveCorrect(), after.getConsecutiveCorrect());
        assertEquals(before.getLapses(), after.getLapses());
        assertEquals(before.getLastReviewedAt(), after.getLastReviewedAt());
        List<ReviewLog> history = logs.findByWord(weak.getId());
        assertEquals(6, history.size());
        assertTrue(history.stream().allMatch(log -> log.getKind() == ReviewKind.PRACTICE), history.toString());
    }

    @Test
    void practiceEarnsHalfTheXpAndDoesNotCountTowardsTheReviewGoal() throws SQLException {
        WordCard weak = words.insert(weakWord("abate", START.plusDays(4)));
        service.startSession(deck.getId(), ReviewMode.WEAK_WORDS, 0);

        ReviewOutcome practice = answer(service.nextWord(deck.getId(), ReviewMode.WEAK_WORDS).orElseThrow(),
            weak.getChinese(), ReviewRating.GOOD);

        // A Good review of a matching answer earns 5 + 4 + 8 = 17 XP.
        assertEquals(17 / 2, practice.xpEarned());
        assertEquals(GoalService.practiceXp(logs.findByWord(weak.getId()).get(0)), practice.xpEarned());
        assertTrue(practice.xpEarned() > 0);
        assertEquals(0, goals.getTodayProgress(deck.getId()).reviewedCount());
        assertEquals(0, goals.reviewCount(deck.getId(), 100));
        assertEquals(0, goals.getTodayProgress(deck.getId()).currentStreak(), "practice alone is not a review day");
        assertEquals(practice.xpEarned(), goals.totalXp(deck.getId()));
        assertTrue(practice.unlockedAchievements().isEmpty());

        // The same answer to a due word is a review and earns twice as much (and the first-review badge).
        WordCard due = words.insert(weakWord("laud", START.minusDays(1)));
        service.startSession(deck.getId(), ReviewMode.WEAK_WORDS, 0);
        ReviewOutcome review = answer(service.nextWord(deck.getId(), ReviewMode.WEAK_WORDS).orElseThrow(),
            due.getChinese(), ReviewRating.GOOD);
        assertFalse(review.isPractice());
        assertEquals(1, goals.getTodayProgress(deck.getId()).reviewedCount());
        int reviewXp = review.xpEarned() - review.unlockedAchievements().stream().mapToInt(a -> a.xpReward()).sum();
        assertEquals(2 * practice.xpEarned() + 1, reviewXp, "half, rounded down, of " + reviewXp);
    }

    @Test
    void aFailedPracticeBringsTheWordBackTomorrowButNeverPushesItLater() throws SQLException {
        WordCard weak = words.insert(weakWord("abate", START.plusDays(4)));
        WordCard before = stored(weak);
        service.startSession(deck.getId(), ReviewMode.WEAK_WORDS, 0);

        // A wrong answer counts as Again, whatever was clicked.
        WordCard shown = service.nextWord(deck.getId(), ReviewMode.WEAK_WORDS).orElseThrow();
        service.submitAnswer(shown.getId(), "完全错误", ReviewMode.WEAK_WORDS, clock.now());
        Map<ReviewRating, IntervalPreview> previews = service.previewRatings(shown.getId());
        assertEquals(IntervalPreview.days(1), previews.get(ReviewRating.GOOD), "every rating counts as Again");
        assertTrue(service.rateCurrent(shown.getId(), ReviewRating.GOOD).isPractice());

        WordCard failed = stored(weak);
        assertEquals(TOMORROW, failed.getNextReviewAt(), "due when the next study day starts");
        assertEquals(CardState.REVIEW, failed.getState(), "not a lapse: no relearning, no new memory");
        assertEquals(before.getStability(), failed.getStability());
        assertEquals(before.getLapses(), failed.getLapses());
        assertEquals(before.getLastReviewedAt(), failed.getLastReviewedAt());

        // Practicing again later today, failed or not, never moves it later.
        clock.advance(Duration.ofHours(6));
        service.startSession(deck.getId(), ReviewMode.WEAK_WORDS, 0);
        answer(service.nextWord(deck.getId(), ReviewMode.WEAK_WORDS).orElseThrow(), "完全错误", ReviewRating.AGAIN);
        service.startSession(deck.getId(), ReviewMode.WEAK_WORDS, 0);
        answer(service.nextWord(deck.getId(), ReviewMode.WEAK_WORDS).orElseThrow(), weak.getChinese(), ReviewRating.EASY);
        assertEquals(TOMORROW, stored(weak).getNextReviewAt());

        // A word already due tomorrow stays there.
        WordCard soon = words.insert(weakWord("laud", TOMORROW));
        service.startSession(deck.getId(), ReviewMode.WEAK_WORDS, 0);
        int practiced = 0;
        for (Optional<WordCard> next = service.nextWord(deck.getId(), ReviewMode.WEAK_WORDS);
             next.isPresent() && practiced < 10; next = service.nextWord(deck.getId(), ReviewMode.WEAK_WORDS)) {
            answer(next.get(), "完全错误", ReviewRating.AGAIN);
            practiced++;
        }
        assertEquals(2, practiced);
        assertEquals(TOMORROW, stored(soon).getNextReviewAt());
    }

    @Test
    void thePracticeButtonsShowTheUnchangedDueDate() throws SQLException {
        WordCard weak = words.insert(weakWord("abate", START.plusDays(4)));
        service.startSession(deck.getId(), ReviewMode.WEAK_WORDS, 0);
        WordCard shown = service.nextWord(deck.getId(), ReviewMode.WEAK_WORDS).orElseThrow();
        service.submitAnswer(shown.getId(), weak.getChinese(), ReviewMode.WEAK_WORDS, clock.now());

        Map<ReviewRating, IntervalPreview> previews = service.previewRatings(shown.getId());

        assertEquals(IntervalPreview.days(1), previews.get(ReviewRating.AGAIN));
        for (ReviewRating rating : List.of(ReviewRating.HARD, ReviewRating.GOOD, ReviewRating.EASY)) {
            assertEquals(IntervalPreview.days(4), previews.get(rating), rating.name());
        }
    }

    @Test
    void aWeakWordThatIsDueIsReviewedForReal() throws SQLException {
        WordCard due = words.insert(weakWord("abate", START.minusHours(2)));
        service.startSession(deck.getId(), ReviewMode.WEAK_WORDS, 0);

        ReviewOutcome outcome = answer(service.nextWord(deck.getId(), ReviewMode.WEAK_WORDS).orElseThrow(),
            due.getChinese(), ReviewRating.GOOD);

        assertEquals(ReviewKind.REVIEW, outcome.kind());
        WordCard after = stored(due);
        assertEquals(clock.now(), after.getLastReviewedAt());
        assertTrue(after.getNextReviewAt().isAfter(TOMORROW.minusSeconds(1)), "rescheduled: " + after.getNextReviewAt());
        assertEquals(ReviewKind.REVIEW, logs.findByWord(due.getId()).get(0).getKind());
    }

    @Test
    void aWeakWordsSessionShowsEachWordOnceAndEndsWhenAllWereShown() throws SQLException {
        List<Long> weak = new ArrayList<>();
        for (String english : List.of("abate", "laud", "cavil")) {
            weak.add(words.insert(weakWord(english, START.plusDays(3))).getId());
        }
        // Mastered words are not weak, so they are not practiced.
        WordCard mastered = weakWord("lucid", START.plusDays(30));
        mastered.setStability(40);
        mastered.setDifficulty(3);
        mastered.setConsecutiveCorrect(5);
        mastered.setRepetitions(5);
        words.insert(mastered);
        service.startSession(deck.getId(), ReviewMode.WEAK_WORDS, 0);

        List<Long> shown = new ArrayList<>();
        Optional<WordCard> next = service.nextWord(deck.getId(), ReviewMode.WEAK_WORDS);
        while (next.isPresent() && shown.size() < 20) {
            shown.add(next.get().getId());
            answer(next.get(), next.get().getChinese(), ReviewRating.GOOD);
            next = service.nextWord(deck.getId(), ReviewMode.WEAK_WORDS);
        }

        assertEquals(3, shown.size(), "each weak word once, then the session ends: " + shown);
        assertEquals(weak.stream().sorted().toList(), shown.stream().sorted().toList());
        assertEquals(3, service.sessionSummary().cardsReviewed());
    }

    @Test
    void aWeakWordFailedInTheSessionComesBackForItsRelearningStep() throws SQLException {
        WordCard due = words.insert(weakWord("abate", START.minusHours(2)));
        WordCard other = words.insert(weakWord("laud", START.plusDays(3)));
        service.startSession(deck.getId(), ReviewMode.WEAK_WORDS, 0);

        WordCard first = service.nextWord(deck.getId(), ReviewMode.WEAK_WORDS).orElseThrow();
        assertEquals(due.getId(), first.getId(), "the due weak word comes first");
        answer(first, "完全错误", ReviewRating.AGAIN);
        assertEquals(CardState.RELEARNING, stored(due).getState());

        WordCard second = service.nextWord(deck.getId(), ReviewMode.WEAK_WORDS).orElseThrow();
        assertEquals(other.getId(), second.getId());
        answer(second, second.getChinese(), ReviewRating.GOOD);

        // Nothing else is left; the relearning step ends within the learn-ahead window.
        WordCard again = service.nextWord(deck.getId(), ReviewMode.WEAK_WORDS).orElseThrow();
        assertEquals(due.getId(), again.getId());
        assertEquals(ReviewKind.REVIEW, answer(again, again.getChinese(), ReviewRating.GOOD).kind());
        assertEquals(CardState.REVIEW, stored(due).getState());

        assertTrue(service.nextWord(deck.getId(), ReviewMode.WEAK_WORDS).isEmpty());
        assertEquals(2, service.sessionSummary().cardsReviewed());
        assertEquals(3, service.sessionSummary().reviewedCount());
    }

    @Test
    void replayingTheReviewHistorySkipsPractice() {
        ReviewScheduler scheduler = new ReviewScheduler();
        LocalDateTime first = LocalDateTime.of(2026, 3, 1, 9, 0);
        List<ReviewLog> reviews = List.of(
            log(first, ReviewRating.GOOD, ReviewKind.LEARN),
            log(first.plusDays(3), ReviewRating.GOOD, ReviewKind.REVIEW));
        List<ReviewLog> withPractice = new ArrayList<>(reviews);
        withPractice.add(log(first.plusDays(1), ReviewRating.AGAIN, ReviewKind.PRACTICE));
        withPractice.add(log(first.plusDays(2), ReviewRating.EASY, ReviewKind.PRACTICE));

        WordCard replayed = WordCard.createNew(1, "abate", "减弱", clock.now());
        replayed.setId(1);
        scheduler.replay(replayed, withPractice);
        WordCard expected = WordCard.createNew(1, "abate", "减弱", clock.now());
        expected.setId(1);
        scheduler.replay(expected, reviews);

        assertEquals(expected.getStability(), replayed.getStability());
        assertEquals(expected.getNextReviewAt(), replayed.getNextReviewAt());
        assertEquals(2, replayed.getRepetitions());
        assertEquals(0, replayed.getLapses());
        assertNotEquals(0.0, replayed.getStability());
    }

    private static ReviewLog log(LocalDateTime at, ReviewRating rating, ReviewKind kind) {
        return new ReviewLog(0, 1, at, "", "减弱", rating == ReviewRating.AGAIN ? 0.0 : 1.0, rating, 1000, kind,
            ReviewMode.EN_TO_ZH);
    }

    /** In review, last reviewed yesterday after a lapse, so weak; due at {@code due}. */
    private WordCard weakWord(String english, LocalDateTime due) {
        WordCard card = WordCard.createNew(deck.getId(), english, "释义" + english, clock.now());
        card.setState(CardState.REVIEW);
        card.setStability(4);
        card.setDifficulty(6);
        card.setRepetitions(4);
        card.setConsecutiveCorrect(1);
        card.setLapses(1);
        card.setIntervalDays(4);
        card.setLastReviewedAt(START.minusDays(1));
        card.setNextReviewAt(due);
        return card;
    }

    /** Takes five seconds to answer, then rates. */
    private ReviewOutcome answer(WordCard word, String answer, ReviewRating rating) {
        LocalDateTime shownAt = clock.now();
        clock.advance(Duration.ofSeconds(5));
        service.submitAnswer(word.getId(), answer, ReviewMode.WEAK_WORDS, shownAt);
        return service.rateCurrent(word.getId(), rating);
    }

    private WordCard stored(WordCard word) throws SQLException {
        return words.findById(word.getId()).orElseThrow();
    }
}
