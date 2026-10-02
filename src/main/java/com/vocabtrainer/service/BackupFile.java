package com.vocabtrainer.service;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.List;

/**
 * The JSON backup of one deck, format version 2.
 *
 * <p>Date-times are ISO-8601 local date-time strings exactly as stored in SQLite (see
 * {@code DateTimeUtil}); goal dates are ISO-8601 dates. The entry records also read version 1
 * files, which held only the text fields of each word, wrote every value as a string and keyed
 * review logs by {@code wordEnglish}: absent fields read as {@code null}.
 *
 * <p>Words also carry their FSRS state ({@code cardState}, {@code stability}, {@code difficulty},
 * {@code learningStep}) next to the SM-2 fields. Backups written before FSRS lack it; restoring
 * them derives it like the schema upgrade does. Older versions of the app ignore the extra fields.
 */
record BackupFile(
    String format,
    int version,
    String exportedAt,
    DeckEntry deck,
    List<WordEntry> words,
    List<ReviewLogEntry> reviewLogs,
    List<DailyGoalEntry> dailyGoals,
    List<AchievementEntry> achievements
) {
    static final String FORMAT = "vocaboost-backup";
    static final int VERSION = 2;

    record DeckEntry(String name) {
    }

    record WordEntry(
        String english,
        String chinese,
        String phonetic,
        String partOfSpeech,
        String exampleSentence,
        String note,
        String tags,
        String addedAt,
        String lastReviewedAt,
        String nextReviewAt,
        Double easinessFactor,
        Integer intervalDays,
        Integer repetitions,
        Integer consecutiveCorrect,
        Integer lapses,
        Boolean archived,
        String cardState,
        Double stability,
        Double difficulty,
        Integer learningStep
    ) {
    }

    /**
     * @param kind      {@code LEARN}, {@code REVIEW} or {@code PRACTICE}; absent in backups written before
     *                  it was logged, which restore as {@code REVIEW}
     * @param direction {@code EN_TO_ZH} or {@code ZH_TO_EN}; absent when unknown
     */
    record ReviewLogEntry(
        @JsonAlias("wordEnglish") String english,
        String reviewedAt,
        String userAnswer,
        String correctAnswer,
        Double similarity,
        String rating,
        Long elapsedMillis,
        String kind,
        String direction
    ) {
    }

    record DailyGoalEntry(
        String date,
        Integer reviewGoal,
        Integer newWordGoal,
        Integer sessionGoal,
        Integer reviewedCount,
        Integer correctCount,
        Integer newWordsCount,
        Integer xpEarned,
        Boolean completed
    ) {
    }

    record AchievementEntry(
        String code,
        String name,
        String description,
        String unlockedAt,
        Integer xpReward
    ) {
    }
}
