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

    public ReviewLog(long id, long wordId, LocalDateTime reviewedAt, String userAnswer,
                     String correctAnswer, double similarity, ReviewRating rating, long elapsedMillis) {
        this.id = id;
        this.wordId = wordId;
        this.reviewedAt = reviewedAt;
        this.userAnswer = userAnswer;
        this.correctAnswer = correctAnswer;
        this.similarity = similarity;
        this.rating = rating;
        this.elapsedMillis = elapsedMillis;
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
}