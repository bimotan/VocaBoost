package com.vocabtrainer.service;

import com.vocabtrainer.domain.WordCard;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Random;

public class WordSelector {
    private final Random random;

    public WordSelector() {
        this(new Random());
    }

    public WordSelector(Random random) {
        this.random = random;
    }

    public Optional<WordCard> selectNext(List<WordCard> dueWords, LocalDateTime now) {
        if (dueWords == null || dueWords.isEmpty()) {
            return Optional.empty();
        }

        double totalWeight = 0.0;
        double[] weights = new double[dueWords.size()];
        for (int i = 0; i < dueWords.size(); i++) {
            weights[i] = calculateWeight(dueWords.get(i), now);
            totalWeight += weights[i];
        }

        if (totalWeight <= 0.0) {
            return Optional.of(dueWords.get(random.nextInt(dueWords.size())));
        }

        double point = random.nextDouble() * totalWeight;
        double cumulative = 0.0;
        for (int i = 0; i < dueWords.size(); i++) {
            cumulative += weights[i];
            if (cumulative >= point) {
                return Optional.of(dueWords.get(i));
            }
        }
        return Optional.of(dueWords.get(dueWords.size() - 1));
    }

    public double calculateWeight(WordCard word, LocalDateTime now) {
        double base = 1.0 - word.calculateMemoryStrength(now);
        double novelty = word.getConsecutiveCorrect() < 3 ? 1.5 : 1.0;
        double overdue = 1.0 + Math.max(0, overdueDays(word, now)) * 0.2;
        double lapsePressure = 1.0 + Math.min(3, word.getLapses()) * 0.35;
        return Math.max(0.01, base * novelty * overdue * lapsePressure);
    }

    private long overdueDays(WordCard word, LocalDateTime now) {
        LocalDateTime nextReview = word.getNextReviewAt();
        if (nextReview == null || now.isBefore(nextReview)) {
            return 0;
        }
        return Duration.between(nextReview, now).toDays();
    }
}
