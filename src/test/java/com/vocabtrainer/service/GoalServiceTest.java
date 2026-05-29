package com.vocabtrainer.service;

import com.vocabtrainer.domain.DailyGoalProgress;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.GoalUpdate;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.GoalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void recordsNewWordsReviewsXpAndDailyCompletion() throws Exception {
        GoalService service = serviceAt(LocalDate.of(2026, 5, 28));

        service.recordNewWords(5);
        GoalUpdate last = null;
        for (int i = 0; i < 20; i++) {
            last = service.recordReview(ReviewRating.GOOD, 0.9);
        }

        DailyGoalProgress progress = service.getTodayProgress();
        assertEquals(20, progress.reviewedCount());
        assertEquals(5, progress.newWordsCount());
        assertTrue(progress.completed());
        assertTrue(last.dailyGoalCompleted());
        assertTrue(progress.totalXp() > 0);
    }

    @Test
    void calculatesReviewStreakAcrossConsecutiveDays() throws Exception {
        DatabaseManager databaseManager = new DatabaseManager(tempDir.resolve("streak.db"));
        databaseManager.initialize();
        GoalRepository repository = new GoalRepository(databaseManager);
        LocalDate today = LocalDate.of(2026, 5, 28);
        for (int i = 0; i < 3; i++) {
            LocalDate date = today.minusDays(i);
            repository.ensure(date, 20, 5, 10);
            repository.addProgress(date, 1, 1, 0, 5);
        }
        GoalService service = new GoalService(repository, clockAt(today));

        assertEquals(3, service.getTodayProgress().currentStreak());
    }

    @Test
    void deckScopedGoalsDoNotPolluteEachOther() throws Exception {
        DatabaseManager databaseManager = new DatabaseManager(tempDir.resolve("deck-goals.db"));
        databaseManager.initialize();
        DeckRepository deckRepository = new DeckRepository(databaseManager);
        Deck defaultDeck = deckRepository.ensureDefaultDeck();
        Deck secondDeck = deckRepository.create("Second");
        GoalService service = new GoalService(new GoalRepository(databaseManager), clockAt(LocalDate.of(2026, 5, 28)));

        service.recordNewWords(defaultDeck.getId(), 3);
        service.recordReview(defaultDeck.getId(), ReviewRating.GOOD, 0.9);
        service.recordReview(secondDeck.getId(), ReviewRating.AGAIN, 0.1);

        assertEquals(3, service.getTodayProgress(defaultDeck.getId()).newWordsCount());
        assertEquals(1, service.getTodayProgress(defaultDeck.getId()).reviewedCount());
        assertEquals(0, service.getTodayProgress(secondDeck.getId()).newWordsCount());
        assertEquals(1, service.getTodayProgress(secondDeck.getId()).reviewedCount());
        assertTrue(service.getTodayProgress(defaultDeck.getId()).totalXp()
            > service.getTodayProgress(secondDeck.getId()).totalXp());
    }

    private GoalService serviceAt(LocalDate date) throws Exception {
        DatabaseManager databaseManager = new DatabaseManager(tempDir.resolve(date + ".db"));
        databaseManager.initialize();
        return new GoalService(new GoalRepository(databaseManager), clockAt(date));
    }

    private Clock clockAt(LocalDate date) {
        return Clock.fixed(Instant.parse(date + "T09:00:00Z"), ZoneId.of("UTC"));
    }
}
