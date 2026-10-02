package com.vocabtrainer.domain;

import java.time.LocalDateTime;

/**
 * One saved rating of a card.
 *
 * <p>{@link #getRating()} is the rating the user chose; {@link #getEffectiveRating()} is what the
 * schedule used: the chosen rating, capped by the answer check unless the user overrode it (and in
 * Mixed mode an Easy recognition counts as Good). A review is correct when it did not count as Again,
 * see {@link #isCorrect()}; every count of correct answers uses that rule.
 */
public class ReviewLog {
    private long id;
    private long wordId;
    private LocalDateTime reviewedAt;
    private String userAnswer;
    private String correctAnswer;
    private double similarity;
    private ReviewRating rating;
    private long elapsedMillis;
    private ReviewKind kind;
    private ReviewMode direction;
    private ReviewRating effectiveRating;
    private boolean overridden;

    /** A {@link ReviewKind#REVIEW} log whose question direction is unknown. */
    public ReviewLog(long id, long wordId, LocalDateTime reviewedAt, String userAnswer,
                     String correctAnswer, double similarity, ReviewRating rating, long elapsedMillis) {
        this(id, wordId, reviewedAt, userAnswer, correctAnswer, similarity, rating, elapsedMillis,
            ReviewKind.REVIEW, null);
    }

    /**
     * @param kind      what the review was; null reads as {@link ReviewKind#REVIEW}
     * @param direction {@link ReviewMode#EN_TO_ZH}, {@link ReviewMode#ZH_TO_EN} or
     *                  {@link ReviewMode#CLOZE}, the way the question was asked; null if unknown
     *                  (logs of older versions)
     */
    public ReviewLog(long id, long wordId, LocalDateTime reviewedAt, String userAnswer,
                     String correctAnswer, double similarity, ReviewRating rating, long elapsedMillis,
                     ReviewKind kind, ReviewMode direction) {
        this(id, wordId, reviewedAt, userAnswer, correctAnswer, similarity, rating, elapsedMillis, kind, direction,
            null, false);
    }

    /**
     * @param effectiveRating the rating the schedule used; null if not recorded (logs of older
     *                        versions), which reads as {@code rating} capped by {@code similarity}
     * @param overridden      whether the user overrode the answer check ("I was right"), so the
     *                        chosen rating counted although the answer did not match
     */
    public ReviewLog(long id, long wordId, LocalDateTime reviewedAt, String userAnswer,
                     String correctAnswer, double similarity, ReviewRating rating, long elapsedMillis,
                     ReviewKind kind, ReviewMode direction, ReviewRating effectiveRating, boolean overridden) {
        if (direction != null && !direction.isDirection()) {
            throw new IllegalArgumentException("A question direction is EN_TO_ZH, ZH_TO_EN or CLOZE, not " + direction);
        }
        this.id = id;
        this.wordId = wordId;
        this.reviewedAt = reviewedAt;
        this.userAnswer = userAnswer;
        this.correctAnswer = correctAnswer;
        this.similarity = similarity;
        this.rating = rating;
        this.elapsedMillis = elapsedMillis;
        this.kind = kind == null ? ReviewKind.REVIEW : kind;
        this.direction = direction;
        this.effectiveRating = effectiveRating;
        this.overridden = overridden;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getWordId() {
        return wordId;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public String getUserAnswer() {
        return userAnswer;
    }

    public String getCorrectAnswer() {
        return correctAnswer;
    }

    public double getSimilarity() {
        return similarity;
    }

    /** The rating the user chose. */
    public ReviewRating getRating() {
        return rating;
    }

    /**
     * The rating the schedule used. A log of an older version did not record it: it reads as the
     * chosen rating capped by the answer similarity ({@link ReviewRating#maxForSimilarity}), which is
     * how those versions scheduled it.
     */
    public ReviewRating getEffectiveRating() {
        return effectiveRating != null ? effectiveRating : rating.atMost(ReviewRating.maxForSimilarity(similarity));
    }

    /** The effective rating as recorded; null for a log of an older version, see {@link #getEffectiveRating()}. */
    public ReviewRating getRecordedEffectiveRating() {
        return effectiveRating;
    }

    /** Whether the user overrode the answer check, so the chosen rating counted as it was. */
    public boolean isOverridden() {
        return overridden;
    }

    /**
     * Whether the answer counts as correct: the review did not count as Again. Session accuracy,
     * goals, statistics and the report all use this rule; {@code ReviewLogRepository} has it in SQL.
     */
    public boolean isCorrect() {
        return getEffectiveRating() != ReviewRating.AGAIN;
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }

    public ReviewKind getKind() {
        return kind;
    }

    /** How the question was asked (English to Chinese, Chinese to English or cloze); null if unknown. */
    public ReviewMode getDirection() {
        return direction;
    }
}
