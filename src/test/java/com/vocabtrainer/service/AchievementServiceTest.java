package com.vocabtrainer.service;

import com.vocabtrainer.domain.Achievement;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.GoalUpdate;
import com.vocabtrainer.domain.ReviewKind;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.AchievementRepository;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.GoalRepository;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.repository.WordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AchievementServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-28T09:00:00Z"), ZoneId.of("UTC"));
    private static final LocalDate TODAY = LocalDate.of(2026, 5, 28);

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private WordRepository words;
    private ReviewLogRepository logs;
    private AchievementRepository achievementRepository;
    private GoalService goals;
    private AchievementService achievements;
    private DeckRepository decks;

    @BeforeEach
    void openDatabase() throws SQLException {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("achievements.db"));
        decks = new DeckRepository(databaseManager);
        words = new WordRepository(databaseManager);
        logs = new ReviewLogRepository(databaseManager);
        achievementRepository = new AchievementRepository(databaseManager);
        goals = new GoalService(new GoalRepository(databaseManager), logs, CLOCK);
        achievements = new AchievementService(achievementRepository, goals, CLOCK);
    }

    @Test
    void reviewBadgesCountTheDecksReviewsButNotPractice() throws Exception {
        Deck deck = decks.ensureDefaultDeck();
        WordCard word = words.save(WordCard.createNew(deck.getId(), "lucid", "清晰的"));
        for (int i = 0; i < 5; i++) {
            logs.insert(log(word, TODAY.atTime(8, i), ReviewKind.PRACTICE));
        }
        List<Achievement> unlocked = List.of();
        for (int i = 0; i < 10; i++) {
            unlocked = review(deck, word, TODAY.atTime(9, i));
            if (i == 8) {
                assertTrue(unlocked.isEmpty(), "9 reviews and 5 practices are not 10 reviews: " + unlocked);
            }
        }

        assertEquals(List.of("review_10"), codes(unlocked));
        assertEquals(List.of("first_review", "review_10"), codes(achievements.getUnlockedAchievements(deck.getId())));
        assertEquals(10 + 15 + 10 * reviewXp(), goals.totalXp(deck.getId()), "each badge's XP once");
    }

    @Test
    void streakBadgesBelongToNoDeckAndAreUnlockedOnce() throws Exception {
        Deck gre = decks.ensureDefaultDeck();
        Deck toefl = decks.create("TOEFL");
        WordCard greWord = words.save(WordCard.createNew(gre.getId(), "lucid", "清晰的"));
        WordCard toeflWord = words.save(WordCard.createNew(toefl.getId(), "laud", "赞扬"));
        logs.insert(log(greWord, TODAY.minusDays(2).atTime(20, 0), ReviewKind.REVIEW));
        logs.insert(log(toeflWord, TODAY.minusDays(1).atTime(20, 0), ReviewKind.REVIEW));

        List<Achievement> unlocked = review(gre, greWord, TODAY.atTime(8, 0));
        assertTrue(codes(unlocked).contains("streak_3"), "three study days across two decks: " + codes(unlocked));
        assertFalse(codes(review(toefl, toeflWord, TODAY.atTime(8, 30))).contains("streak_3"), "not again in TOEFL");

        assertTrue(achievementRepository.exists(AchievementRepository.NO_DECK, "streak_3"));
        assertFalse(achievementRepository.exists(gre.getId(), "streak_3"));
        assertTrue(codes(achievements.getUnlockedAchievements(toefl.getId())).contains("streak_3"),
            "shown in every deck");
    }

    @Test
    void aStreakBadgeAnOlderVersionUnlockedInADeckIsNotAwardedAgain() throws Exception {
        Deck gre = decks.ensureDefaultDeck();
        Deck toefl = decks.create("TOEFL");
        Achievement legacy = new Achievement("streak_3", "3-Day Streak", "Reviewed on 3 consecutive days.",
            LocalDateTime.of(2026, 4, 1, 9, 0), 20);
        achievementRepository.insertIfAbsent(gre.getId(), legacy);
        WordCard word = words.save(WordCard.createNew(toefl.getId(), "laud", "赞扬"));
        logs.insert(log(word, TODAY.minusDays(2).atTime(20, 0), ReviewKind.REVIEW));
        logs.insert(log(word, TODAY.minusDays(1).atTime(20, 0), ReviewKind.REVIEW));

        List<Achievement> unlocked = review(toefl, word, TODAY.atTime(8, 0));

        assertFalse(codes(unlocked).contains("streak_3"));
        assertFalse(achievementRepository.exists(AchievementRepository.NO_DECK, "streak_3"));
        assertEquals(List.of(legacy), achievements.getUnlockedAchievements(gre.getId()), "still in its deck");
        assertTrue(achievements.getUnlockedAchievements(toefl.getId()).contains(legacy), "and shown in every deck");
    }

    /** Saves a review of {@code word} at {@code at} and evaluates the badges, as a rating does. */
    private List<Achievement> review(Deck deck, WordCard word, LocalDateTime at) throws SQLException {
        GoalUpdate update = goals.recordReview(deck.getId(), logs.insert(log(word, at, ReviewKind.REVIEW)));
        return achievements.evaluate(deck.getId(), update.progress(), false, false);
    }

    private static ReviewLog log(WordCard word, LocalDateTime at, ReviewKind kind) {
        return new ReviewLog(0, word.getId(), at, "清晰的", "清晰的", 1.0, ReviewRating.GOOD, 1000, kind,
            ReviewMode.EN_TO_ZH);
    }

    /** The XP of one correct Good review with a perfect answer. */
    private static int reviewXp() {
        return 5 + ReviewRating.GOOD.getQuality() + 8;
    }

    private static List<String> codes(List<Achievement> unlocked) {
        return unlocked.stream().map(Achievement::code).toList();
    }
}
