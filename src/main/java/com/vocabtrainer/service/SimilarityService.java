package com.vocabtrainer.service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public class SimilarityService {
    private static final Pattern MEANING_SEPARATOR_PATTERN = Pattern.compile("[;\\uFF1B,\\uFF0C/\\u3001]+");

    public double calculate(String userAnswer, String correctAnswer) {
        String left = normalize(userAnswer);
        String[] meanings = splitMeanings(correctAnswer);
        if (left.isEmpty() && meanings.length == 0) {
            return 1.0;
        }
        if (left.isEmpty() || meanings.length == 0) {
            return 0.0;
        }
        return Arrays.stream(meanings)
            .map(this::normalize)
            .filter(value -> !value.isEmpty())
            .mapToDouble(right -> calculateSingle(left, right))
            .max()
            .orElse(0.0);
    }

    private double calculateSingle(String left, String right) {
        if (left.equals(right)) {
            return 1.0;
        }
        double jaccard = jaccard(left, right);
        double levenshtein = levenshteinSimilarity(left, right);
        return 0.45 * jaccard + 0.55 * levenshtein;
    }

    public int evaluateQuality(double similarity) {
        if (similarity > 0.9) return 5;
        if (similarity > 0.7) return 4;
        if (similarity > 0.5) return 3;
        if (similarity > 0.3) return 2;
        return 1;
    }

    public String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[\\p{Punct}\\p{IsPunctuation}]", "")
            .replaceAll("\\s+", "");
    }

    public String[] splitMeanings(String value) {
        if (value == null || value.isBlank()) {
            return new String[0];
        }
        return MEANING_SEPARATOR_PATTERN.split(value.trim());
    }

    private double jaccard(String left, String right) {
        Set<Integer> leftSet = toCodePointSet(left);
        Set<Integer> rightSet = toCodePointSet(right);
        Set<Integer> intersection = new HashSet<>(leftSet);
        intersection.retainAll(rightSet);
        Set<Integer> union = new HashSet<>(leftSet);
        union.addAll(rightSet);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private double levenshteinSimilarity(String left, String right) {
        int[] leftPoints = left.codePoints().toArray();
        int[] rightPoints = right.codePoints().toArray();
        int maxLength = Math.max(leftPoints.length, rightPoints.length);
        if (maxLength == 0) {
            return 1.0;
        }
        int distance = levenshteinDistance(leftPoints, rightPoints);
        return Math.max(0.0, 1.0 - distance / (double) maxLength);
    }

    private int levenshteinDistance(int[] left, int[] right) {
        int[] previous = new int[right.length + 1];
        int[] current = new int[right.length + 1];
        for (int j = 0; j <= right.length; j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length; i++) {
            current[0] = i;
            for (int j = 1; j <= right.length; j++) {
                int substitution = left[i - 1] == right[j - 1] ? 0 : 1;
                current[j] = Math.min(
                    Math.min(current[j - 1] + 1, previous[j] + 1),
                    previous[j - 1] + substitution
                );
            }
            int[] temp = previous;
            previous = current;
            current = temp;
        }
        return previous[right.length];
    }

    private Set<Integer> toCodePointSet(String value) {
        Set<Integer> result = new HashSet<>();
        value.codePoints().forEach(result::add);
        return result;
    }
}
