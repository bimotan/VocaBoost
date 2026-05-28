package com.vocabtrainer.service;

import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Random;

public class ReviewScheduler {
    private static final double MIN_EASINESS = 1.3;
    private static final int[] INITIAL_INTERVALS = {1, 3, 7};
    private final Random random;

    public ReviewScheduler() {
        this(new Random());
    }

    public ReviewScheduler(Random random) {
        this.random = random;
    }

    public void applyRating(WordCard word, ReviewRating rating, LocalDateTime reviewedAt) {
        int quality = rating.getQuality();
        word.setRepetitions(word.getRepetitions() + 1);

        if (quality < 3) {
            word.setConsecutiveCorrect(0);
            word.setIntervalDays(1);
            word.setLapses(word.getLapses() + 1);
        } else {
            int consecutive = word.getConsecutiveCorrect() + 1;
            word.setConsecutiveCorrect(consecutive);
            double adjusted = word.getEasinessFactor()
                + (0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02));
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
        if (dueWords == null || dueWords.isEmpty()) {
            return Optional.empty();
        }

        double totalUrgency = 0.0;
        double[] weights = new double[dueWords.size()];
        for (int i = 0; i < dueWords.size(); i++) {
            double urgency = calculateUrgency(dueWords.get(i), now);
            weights[i] = urgency;
            totalUrgency += urgency;
        }

        if (totalUrgency <= 0.0) {
            return Optional.of(dueWords.get(random.nextInt(dueWords.size())));
        }

        double point = random.nextDouble() * totalUrgency;
        double cumulative = 0.0;
        for (int i = 0; i < dueWords.size(); i++) {
            cumulative += weights[i];
            if (cumulative >= point) {
                return Optional.of(dueWords.get(i));
            }
        }
        return Optional.of(dueWords.get(dueWords.size() - 1));
    }

    private double calculateUrgency(WordCard word, LocalDateTime now) {
        double base = 1.0 - word.calculateMemoryStrength(now);
        double novelty = word.getConsecutiveCorrect() < 3 ? 1.5 : 1.0;
        double overdue = 1.0 + Math.max(0, overdueDays(word, now)) * 0.2;
        return Math.max(0.01, base * novelty * overdue);
    }

    private long overdueDays(WordCard word, LocalDateTime now) {
        LocalDateTime nextReview = word.getNextReviewAt();
        if (nextReview == null || now.isBefore(nextReview)) {
            return 0;
        }
        return Duration.between(nextReview, now).toDays();
    }
}