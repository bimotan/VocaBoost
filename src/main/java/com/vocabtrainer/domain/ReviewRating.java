package com.vocabtrainer.domain;

public enum ReviewRating {
    AGAIN("Again", 1, 1),
    HARD("Hard", 3, 2),
    GOOD("Good", 4, 3),
    EASY("Easy", 5, 4);

    private final String label;
    private final int quality;
    private final int grade;

    ReviewRating(String label, int quality, int grade) {
        this.label = label;
        this.quality = quality;
        this.grade = grade;
    }

    public String getLabel() {
        return label;
    }

    /** The old SM-2 quality (1, 3, 4, 5); review XP still uses it. */
    public int getQuality() {
        return quality;
    }

    /** The FSRS grade: Again 1, Hard 2, Good 3, Easy 4. */
    public int getGrade() {
        return grade;
    }

    /** The rating with FSRS grade {@code grade} (1 to 4). */
    public static ReviewRating ofGrade(int grade) {
        for (ReviewRating rating : values()) {
            if (rating.grade == grade) {
                return rating;
            }
        }
        throw new IllegalArgumentException("No rating has grade " + grade);
    }
}
