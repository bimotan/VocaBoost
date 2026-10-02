package com.vocabtrainer.domain;

import java.time.LocalDateTime;

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

    /** A {@link ReviewKind#REVIEW} log whose question direction is unknown. */
    public ReviewLog(long id, long wordId, LocalDateTime reviewedAt, String userAnswer,
                     String correctAnswer, double similarity, ReviewRating rating, long elapsedMillis) {
        this(id, wordId, reviewedAt, userAnswer, correctAnswer, similarity, rating, elapsedMillis,
            ReviewKind.REVIEW, null);
    }

    /**
     * @param kind      what the review was; null reads as {@link ReviewKind#REVIEW}
     * @param direction {@link ReviewMode#EN_TO_ZH} or {@link ReviewMode#ZH_TO_EN}, the way the
     *                  question was asked; null if unknown (logs of older versions)
     */
    public ReviewLog(long id, long wordId, LocalDateTime reviewedAt, String userAnswer,
                     String correctAnswer, double similarity, ReviewRating rating, long elapsedMillis,
                     ReviewKind kind, ReviewMode direction) {
        if (direction != null && direction != ReviewMode.EN_TO_ZH && direction != ReviewMode.ZH_TO_EN) {
            throw new IllegalArgumentException("A question direction is EN_TO_ZH or ZH_TO_EN, not " + direction);
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

    public ReviewRating getRating() {
        return rating;
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }

    public ReviewKind getKind() {
        return kind;
    }

    /** How the question was asked (English to Chinese or Chinese to English); null if unknown. */
    public ReviewMode getDirection() {
        return direction;
    }
}
