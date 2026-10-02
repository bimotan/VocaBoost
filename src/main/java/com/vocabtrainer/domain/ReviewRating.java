package com.vocabtrainer.domain;

public enum ReviewRating {
    AGAIN("Again", 1, 1),
    HARD("Hard", 3, 2),
    GOOD("Good", 4, 3),
    EASY("Easy", 5, 4);

    /** The least answer similarity a rating above Again needs; below it any rating counts as Again. */
    public static final double MIN_SIMILARITY_HARD = 0.55;
    /** The least answer similarity a rating above Hard needs. */
    public static final double MIN_SIMILARITY_GOOD = 0.75;
    /** The least answer similarity Easy needs. */
    public static final double MIN_SIMILARITY_EASY = 0.9;

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

    /** This rating, or {@code cap} if that is lower. */
    public ReviewRating atMost(ReviewRating cap) {
        return cap.grade < grade ? cap : this;
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

    /**
     * The best rating an answer of {@code similarity} (0 to 1) can count as: Easy from
     * {@value #MIN_SIMILARITY_EASY}, Good from {@value #MIN_SIMILARITY_GOOD}, Hard from
     * {@value #MIN_SIMILARITY_HARD}, otherwise Again (also for a similarity that is not a finite number).
     */
    public static ReviewRating maxForSimilarity(double similarity) {
        if (!Double.isFinite(similarity) || similarity < MIN_SIMILARITY_HARD) {
            return AGAIN;
        }
        return similarity >= MIN_SIMILARITY_EASY ? EASY : similarity >= MIN_SIMILARITY_GOOD ? GOOD : HARD;
    }
}
