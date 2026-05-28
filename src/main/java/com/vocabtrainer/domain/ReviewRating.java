package com.vocabtrainer.domain;

public enum ReviewRating {
    AGAIN("Again", 1),
    HARD("Hard", 3),
    GOOD("Good", 4),
    EASY("Easy", 5);

    private final String label;
    private final int quality;

    ReviewRating(String label, int quality) {
        this.label = label;
        this.quality = quality;
    }

    public String getLabel() {
        return label;
    }

    public int getQuality() {
        return quality;
    }
}