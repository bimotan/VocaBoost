package com.vocabtrainer.repository;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.HardWordStat;
import com.vocabtrainer.domain.ReviewKind;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SQL counts of correct answers use the rule of {@link ReviewLog#isCorrect()}, for logs with an
 * effective rating and for logs of older versions without one (review finding A4).
 */
class CorrectAnswerAgreementTest {
    private static final LocalDateTime DAY = LocalDateTime.of(2026, 3, 10, 0, 0);

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    @Test
    void sqlCountsTheSameAnswersAsCorrectAsTheDomain() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("agreement.db"));
        Deck deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        WordCard word = new WordRepository(databaseManager).insert(WordCard.createNew(deck.getId(), "lucid", "清晰的"));
        ReviewLogRepository repository = new ReviewLogRepository(databaseManager);
        List<ReviewRating> effectiveRatings = new ArrayList<>(Arrays.asList(ReviewRating.values()));
        effectiveRatings.add(null);
        List<ReviewLog> logs = new ArrayList<>();
        int minute = 0;
        for (ReviewRating rating : ReviewRating.values()) {
            for (double similarity : new double[] {0.0, 0.54, 0.55, 0.8, 1.0}) {
                for (ReviewRating effective : effectiveRatings) {
                    for (boolean overridden : new boolean[] {false, true}) {
                        logs.add(repository.insert(new ReviewLog(0, word.getId(), DAY.plusMinutes(minute++), "答案",
                            "清晰的", similarity, rating, 1000, ReviewKind.REVIEW, ReviewMode.EN_TO_ZH, effective,
                            overridden)));
                    }
                }
            }
        }
        int correct = (int) logs.stream().filter(ReviewLog::isCorrect).count();
        assertTrue(correct > 0 && correct < logs.size());

        assertEquals(correct, repository.countCorrectSince(deck.getId(), DAY));
        assertEquals(correct, repository.countCorrectSince(DAY));
        assertEquals(List.of(new ReviewLogRepository.DailyCount(DAY.toLocalDate(), logs.size(), correct)),
            repository.dailyCounts(deck.getId(), DAY));
        assertEquals(List.of(new ReviewLogRepository.DailyCount(DAY.toLocalDate(), logs.size(), correct)),
            repository.dailyCounts(0, DAY));
        HardWordStat hardest = repository.hardestWords(deck.getId(), 1).get(0);
        assertEquals(logs.size() - correct, hardest.againCount());
    }
}
