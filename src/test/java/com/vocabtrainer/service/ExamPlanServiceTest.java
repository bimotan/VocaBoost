package com.vocabtrainer.service;

import com.vocabtrainer.TestClock;
import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.Exam;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.SettingsRepository;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.scheduling.ExamClamp;
import com.vocabtrainer.service.scheduling.IntervalPreview;
import com.vocabtrainer.service.scheduling.SchedulingOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The exam date (review finding G5): every deck's or a deck's own, kept in the settings; the
 * countdown; reviews kept before it, in the rating previews, in the saved schedule and for cards
 * already scheduled after it; and the new words a day it takes to start every new word in time.
 */
class ExamPlanServiceTest {
    /** 10:00 on 2 October; the study day starts at 4 am. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 2, 10, 0);
    private static final LocalDate TODAY = NOW.toLocalDate();

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private final TestClock clock = new TestClock(NOW);
    private Deck deck;
    private Deck other;
    private WordRepository words;
    private ReviewLogRepository logs;
    private SettingsService settingsService;
    private ExamSettings exams;
    private ReviewSettings reviewSettings;
    private ReviewScheduler scheduler;
    private ExamPlanService plans;

    @BeforeEach
    void setUp() throws SQLException {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("exam.db"));
        DeckRepository decks = new DeckRepository(databaseManager);
        deck = decks.ensureDefaultDeck();
        other = decks.create("TOEFL");
        words = new WordRepository(databaseManager);
        logs = new ReviewLogRepository(databaseManager);
        settingsService = new SettingsService(new SettingsRepository(databaseManager));
        exams = new ExamSettings(settingsService);
        reviewSettings = new ReviewSettings(settingsService);
        scheduler = new ReviewScheduler(SchedulingOptions.defaults(), new Random(1), exams::examDate);
        plans = new ExamPlanService(exams, words, logs, reviewSettings, scheduler, clock);
    }

    @Test
    void everyDeckHasTheDefaultExamUnlessItHasItsOwn() {
        assertTrue(plans.examFor(deck.getId()).isEmpty());
        assertTrue(plans.countdown(deck.getId()).isEmpty());

        plans.saveExam(deck.getId(), false, new Exam("GRE", TODAY.plusDays(45)));
        assertEquals(Optional.of(new Exam("GRE", TODAY.plusDays(45))), plans.examFor(other.getId()));
        assertEquals("GRE in 45 days", plans.countdown(deck.getId()).orElseThrow().toDisplayText());

        plans.saveExam(other.getId(), true, new Exam("  TOEFL   iBT ", TODAY.plusDays(10)));
        assertEquals(Optional.of(new Exam("TOEFL iBT", TODAY.plusDays(10))), plans.examFor(other.getId()));
        assertEquals("TOEFL iBT in 10 days", plans.countdown(other.getId()).orElseThrow().toDisplayText());
        assertEquals("GRE in 45 days", plans.countdown(deck.getId()).orElseThrow().toDisplayText());

        // Clearing the deck's own exam goes back to every deck's.
        plans.saveExam(other.getId(), true, null);
        assertEquals("GRE in 45 days", plans.countdown(other.getId()).orElseThrow().toDisplayText());

        // Saving every deck's exam from a deck with its own drops the deck's own, as the goals do.
        plans.saveExam(other.getId(), true, new Exam("TOEFL", TODAY.plusDays(10)));
        plans.saveExam(other.getId(), false, new Exam("GRE", TODAY.plusDays(60)));
        assertTrue(exams.deckExam(other.getId()).isEmpty());
        assertEquals(TODAY.plusDays(60), plans.examFor(other.getId()).orElseThrow().date());

        plans.saveExam(deck.getId(), false, null);
        assertTrue(plans.examFor(deck.getId()).isEmpty());
        assertTrue(plans.examFor(other.getId()).isEmpty());
    }

    @Test
    void theCountdownHandlesTomorrowTodayAndThePast() {
        assertEquals("GRE is tomorrow", countdown(TODAY.plusDays(1)));
        assertEquals("GRE is today", countdown(TODAY));
        assertEquals("GRE was yesterday", countdown(TODAY.minusDays(1)));
        assertEquals("GRE was 3 days ago", countdown(TODAY.minusDays(3)));
        assertTrue(plans.countdown(deck.getId()).orElseThrow().isOver());
        // Days are study days: at 1 am on the 3rd it is still the 2nd until the day rolls over at 4 am.
        clock.set(LocalDateTime.of(2026, 10, 3, 1, 0));
        assertEquals("GRE is tomorrow", countdown(LocalDate.of(2026, 10, 3)));
        clock.set(LocalDateTime.of(2026, 10, 3, 4, 0));
        assertEquals("GRE is today", countdown(LocalDate.of(2026, 10, 3)));
    }

    @Test
    void aSavedDateThatIsNotADateIsIgnored() {
        settingsService.save("exam.date", "next spring");
        assertTrue(exams.defaultExam().isEmpty());
        settingsService.save("exam.date", "2026-11-16");
        assertEquals(new Exam("GRE", LocalDate.of(2026, 11, 16)), exams.defaultExam().orElseThrow(),
            "without a name it is the GRE");
        assertThrows(IllegalArgumentException.class, () -> new Exam("x".repeat(31), TODAY));
    }

    @Test
    void theRatingButtonsAndTheSavedScheduleKeepTheReviewBeforeTheExam() throws SQLException {
        WordCard card = words.insert(reviewCard(deck, "lucid", 40, NOW.minusDays(40), NOW.minusHours(1)));
        ReviewService review = new ReviewService(words, logs, new SimilarityService(), scheduler, null, null, clock,
            reviewSettings, new Random(1));
        review.startSession(deck.getId(), ReviewMode.EN_TO_ZH, 0);
        assertEquals(card.getId(), review.nextWord(deck.getId(), ReviewMode.EN_TO_ZH).orElseThrow().getId());

        review.submitAnswer(card.getId(), "释义", ReviewMode.EN_TO_ZH, NOW);
        Map<ReviewRating, IntervalPreview> withoutExam = review.previewRatings(card.getId());
        assertTrue(withoutExam.get(ReviewRating.GOOD).intervalDays() > 20);
        assertFalse(withoutExam.get(ReviewRating.GOOD).beforeExam());

        plans.saveExam(deck.getId(), false, new Exam("GRE", TODAY.plusDays(20)));
        Map<ReviewRating, IntervalPreview> withExam = review.previewRatings(card.getId());
        IntervalPreview good = withExam.get(ReviewRating.GOOD);
        assertTrue(good.beforeExam());
        assertTrue(good.intervalDays() >= 20 - ExamClamp.FINAL_DAYS && good.intervalDays() < 20, good.toString());

        review.rateCurrent(card.getId(), ReviewRating.GOOD);
        WordCard rated = words.findById(card.getId()).orElseThrow();
        assertEquals(TODAY.plusDays(good.intervalDays()).atTime(4, 0), rated.getNextReviewAt(),
            "the schedule the button showed");
        assertEquals(good.intervalDays(), rated.getIntervalDays());

        // A deck without the exam schedules freely.
        plans.saveExam(deck.getId(), false, null);
        plans.saveExam(other.getId(), true, new Exam("TOEFL", TODAY.plusDays(20)));
        WordCard free = words.insert(reviewCard(deck, "abate", 40, NOW.minusDays(40), NOW.minusHours(1)));
        review.submitAnswer(free.getId(), "释义", ReviewMode.EN_TO_ZH, NOW);
        assertFalse(review.previewRatings(free.getId()).get(ReviewRating.GOOD).beforeExam());
    }

    @Test
    void savingAnExamBringsReviewsScheduledAfterItForward() throws SQLException {
        LocalDate exam = TODAY.plusDays(30);
        WordCard late = words.insert(reviewCard(deck, "lucid", 60, NOW.minusDays(10), TODAY.plusDays(50).atTime(4, 0)));
        WordCard onExamDay = words.insert(reviewCard(deck, "abate", 40, NOW.minusDays(10), exam.atTime(4, 0)));
        WordCard beforeExam = words.insert(reviewCard(deck, "laud", 20, NOW.minusDays(10), TODAY.plusDays(10).atTime(4, 0)));
        WordCard otherDeck = words.insert(reviewCard(other, "lucid", 60, NOW.minusDays(10), TODAY.plusDays(50).atTime(4, 0)));
        WordCard suspended = reviewCard(deck, "zeal", 60, NOW.minusDays(10), TODAY.plusDays(50).atTime(4, 0));
        suspended.setSuspended(true);
        words.insert(suspended);

        int moved = plans.saveExam(deck.getId(), true, new Exam("GRE", exam));

        assertEquals(2, moved, "the card due after the exam and the one due on its day");
        assertEquals(scheduler.dueBeforeExam(late, NOW, exam).orElseThrow(), due(late));
        assertTrue(due(late).toLocalDate().isBefore(exam));
        assertTrue(due(onExamDay).toLocalDate().isBefore(exam));
        assertEquals(TODAY.plusDays(10).atTime(4, 0), due(beforeExam), "due before the exam anyway");
        assertEquals(TODAY.plusDays(50).atTime(4, 0), due(otherDeck), "another deck without the exam");
        assertEquals(TODAY.plusDays(50).atTime(4, 0), due(suspended), "a suspended word");

        // Every deck's exam reaches the other deck too; saving again moves nothing more.
        assertEquals(1, plans.saveExam(deck.getId(), false, new Exam("GRE", exam)));
        assertTrue(due(otherDeck).toLocalDate().isBefore(exam));
        assertEquals(0, plans.saveExam(deck.getId(), false, new Exam("GRE", exam)));
    }

    @Test
    void reviewsScheduledAfterTheExamWithoutTheClampAreBroughtForwardAtStart() throws SQLException {
        LocalDate exam = TODAY.plusDays(30);
        WordCard late = words.insert(reviewCard(deck, "lucid", 60, NOW.minusDays(10), TODAY.plusDays(50).atTime(4, 0)));
        assertEquals(0, plans.bringReviewsBeforeExams(), "no exam");
        assertEquals(TODAY.plusDays(50).atTime(4, 0), due(late));

        // Saved as the dashboard saves it; then a backup restore, an import or the card-state backfill
        // schedules a review after the exam again.
        plans.saveExam(deck.getId(), false, new Exam("GRE", exam));
        WordCard restored = words.insert(reviewCard(deck, "abate", 60, NOW.minusDays(10), TODAY.plusDays(70).atTime(4, 0)));
        WordCard otherDeck = words.insert(reviewCard(other, "laud", 60, NOW.minusDays(10), TODAY.plusDays(70).atTime(4, 0)));

        assertEquals(2, plans.bringReviewsBeforeExams());
        assertEquals(scheduler.dueBeforeExam(restored, NOW, exam).orElseThrow(), due(restored));
        assertTrue(due(otherDeck).toLocalDate().isBefore(exam), "every deck's exam");
        assertEquals(0, plans.bringReviewsBeforeExams(), "nothing more to move");

        // A deck's own exam that comes first; exams that are over or today move nothing.
        assertEquals(Optional.of(exam), exams.firstExamAfter(TODAY));
        plans.saveExam(other.getId(), true, new Exam("TOEFL", TODAY.plusDays(12)));
        assertEquals(Optional.of(TODAY.plusDays(12)), exams.firstExamAfter(TODAY));
        settingsService.save("exam.date.999", "not a date");
        assertEquals(Optional.of(TODAY.plusDays(12)), exams.firstExamAfter(TODAY), "a broken date is skipped");
        plans.saveExam(other.getId(), true, new Exam("TOEFL", TODAY));
        plans.saveExam(deck.getId(), true, new Exam("GRE", TODAY.minusDays(1)));
        exams.clearDefaultExam();
        assertTrue(exams.firstExamAfter(TODAY).isEmpty());
        words.insert(reviewCard(deck, "zeal", 60, NOW.minusDays(10), TODAY.plusDays(70).atTime(4, 0)));
        assertEquals(0, plans.bringReviewsBeforeExams());
    }

    @Test
    void theNewWordPlanCountsTheNewWordsAndTodaysIntroductions() throws SQLException {
        for (int i = 0; i < 100; i++) {
            words.insert(WordCard.createNew(deck.getId(), "word" + (char) ('a' + i / 26) + (char) ('a' + i % 26), "释义"));
        }
        words.insert(reviewCard(deck, "lucid", 10, NOW.minusDays(5), NOW.plusDays(5)));
        WordCard suspended = WordCard.createNew(deck.getId(), "zeal", "热情");
        suspended.setSuspended(true);
        words.insert(suspended);
        assertTrue(plans.newCardPlan(deck.getId()).isEmpty(), "no exam");

        plans.saveExam(deck.getId(), false, new Exam("GRE", TODAY.plusDays(10)));
        NewCardPlan plan = plans.newCardPlan(deck.getId()).orElseThrow();
        assertEquals(100, plan.newWords(), "a suspended new word is not planned");
        assertEquals(10, plan.daysLeft());
        assertEquals(10, plan.wordsPerDay());
        assertTrue(plan.isOnTrack(), "the default limit of 20 is enough");

        reviewSettings.saveNewCardsPerDay(deck.getId(), 4);
        assertFalse(plans.newCardPlan(deck.getId()).orElseThrow().isOnTrack());
        assertEquals(10, plans.applyNewCardPlan(deck.getId()).limitToApply());
        assertEquals(10, reviewSettings.newCardsPerDay(deck.getId()));
        assertEquals(20, reviewSettings.newCardsPerDay(other.getId()), "only this deck's limit");

        plans.saveExam(deck.getId(), false, new Exam("GRE", TODAY));
        assertTrue(plans.newCardPlan(deck.getId()).isEmpty(), "the exam is today");
        assertThrows(IllegalStateException.class, () -> plans.applyNewCardPlan(deck.getId()));
    }

    private String countdown(LocalDate examDate) {
        exams.saveDefaultExam(new Exam("GRE", examDate));
        return plans.countdown(deck.getId()).orElseThrow().toDisplayText();
    }

    private LocalDateTime due(WordCard card) throws SQLException {
        return words.findById(card.getId()).orElseThrow().getNextReviewAt();
    }

    private static WordCard reviewCard(Deck deck, String english, double stability, LocalDateTime lastReview,
                                       LocalDateTime due) {
        WordCard card = WordCard.createNew(deck.getId(), english, "释义");
        card.setState(CardState.REVIEW);
        card.setStability(stability);
        card.setDifficulty(5);
        card.setRepetitions(3);
        card.setConsecutiveCorrect(3);
        card.setIntervalDays((int) Math.round(stability));
        card.setLastReviewedAt(lastReview);
        card.setNextReviewAt(due);
        return card;
    }
}
