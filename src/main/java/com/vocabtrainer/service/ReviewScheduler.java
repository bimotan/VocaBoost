package com.vocabtrainer.service;

import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Random;

public class ReviewScheduler {
    private static final double MIN_EASINESS = 1.3;
    private static final int[] INITIAL_INTERVALS = {1, 3, 7};
    private final WordSelector wordSelector;

    public ReviewScheduler() {
        this(new Random());
    }

    public ReviewScheduler(Random random) {
        this.wordSelector = new WordSelector(random);
    }

    public void applyRating(WordCard word, ReviewRating rating, LocalDateTime reviewedAt) {
        applyRating(word, rating, 1.0, reviewedAt);
    }

    public void applyRating(WordCard word, ReviewRating rating, double similarity, LocalDateTime reviewedAt) {
        double boundedSimilarity = Math.max(0.0, Math.min(1.0, similarity));
        int quality = adjustedQuality(rating, boundedSimilarity);
        word.setRepetitions(word.getRepetitions() + 1);

        if (quality < 3) {
            word.setConsecutiveCorrect(0);
            word.setIntervalDays(1);
            word.setLapses(word.getLapses() + 1);
            double penalty = boundedSimilarity < 0.5 ? (0.5 - boundedSimilarity) * 0.4 : 0.08;
            word.setEasinessFactor(Math.max(MIN_EASINESS, word.getEasinessFactor() - penalty));
        } else {
            int consecutive = word.getConsecutiveCorrect() + 1;
            word.setConsecutiveCorrect(consecutive);
            double adjusted = word.getEasinessFactor()
                + (0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02));
            if (boundedSimilarity < 0.75) {
                adjusted -= (0.75 - boundedSimilarity) * 0.25;
            }
            word.setEasinessFactor(Math.max(MIN_EASINESS, adjusted));

            if (consecutive <= INITIAL_INTERVALS.length) {
                word.setIntervalDays(INITIAL_INTERVALS[consecutive - 1]);
            } else {
                int nextInterval = (int) Math.round(Math.max(1, word.getIntervalDays()) * word.getEasinessFactor());
                word.setIntervalDays(Math.max(1, nextInterval));
            }
        }

        word.setLastReviewedAt(reviewedAt);
        word.setNextReviewAt(reviewedAt.plusDays(word.getIntervalDays()));
    }

    public Optional<WordCard> selectNext(List<WordCard> dueWords, LocalDateTime now) {
        return wordSelector.selectNext(dueWords, now);
    }

    private int adjustedQuality(ReviewRating rating, double similarity) {
        int similarityQuality;
        if (similarity >= 0.9) {
            similarityQuality = 5;
        } else if (similarity >= 0.75) {
            similarityQuality = 4;
        } else if (similarity >= 0.55) {
            similarityQuality = 3;
        } else if (similarity >= 0.35) {
            similarityQuality = 2;
        } else {
            similarityQuality = 1;
        }
        return Math.min(rating.getQuality(), similarityQuality);
    }
}
