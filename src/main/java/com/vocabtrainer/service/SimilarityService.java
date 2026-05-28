package com.vocabtrainer.service;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class SimilarityService {
    public double calculate(String userAnswer, String correctAnswer) {
        String left = normalize(userAnswer);
        String right = normalize(correctAnswer);
        if (left.isEmpty() && right.isEmpty()) {
            return 1.0;
        }
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        if (left.equals(right)) {
            return 1.0;
        }

        Set<Integer> leftSet = toCodePointSet(left);
        Set<Integer> rightSet = toCodePointSet(right);
        Set<Integer> intersection = new HashSet<>(leftSet);
        intersection.retainAll(rightSet);
        Set<Integer> union = new HashSet<>(leftSet);
        union.addAll(rightSet);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    public int evaluateQuality(double similarity) {
        if (similarity > 0.9) return 5;
        if (similarity > 0.7) return 4;
        if (similarity > 0.5) return 3;
        if (similarity > 0.3) return 2;
        return 1;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private Set<Integer> toCodePointSet(String value) {
        Set<Integer> result = new HashSet<>();
        value.codePoints().forEach(result::add);
        return result;
    }
}