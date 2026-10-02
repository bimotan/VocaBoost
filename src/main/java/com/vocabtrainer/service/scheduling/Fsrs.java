package com.vocabtrainer.service.scheduling;

import java.util.Arrays;

/**
 * The FSRS-5 memory model (Free Spaced Repetition Scheduler, version 5) with its published default
 * parameters. Pure and deterministic: it only computes how a review changes a card's memory, which
 * is described by
 * <ul>
 *   <li>stability S: the number of days after which the chance of recall has dropped to 90%,</li>
 *   <li>difficulty D: from 1 (easy) to 10 (hard),</li>
 *   <li>retrievability R: the chance of recall after t days, R = (1 + FACTOR * t / S)^DECAY.</li>
 * </ul>
 * Grades are 1 (Again), 2 (Hard), 3 (Good) and 4 (Easy). The formulas and parameters match the
 * open-spaced-repetition reference implementation of FSRS-5 (py-fsrs 5.x); learning steps, the
 * study day and interval fuzz are left to {@link CardScheduler}.
 */
public final class Fsrs {
    /** The default FSRS-5 parameters w[0] to w[18]. */
    private static final double[] DEFAULT_PARAMETERS = {
        0.40255, 1.18385, 3.173, 15.69105, 7.1949, 0.5345, 1.4604, 0.0046, 1.54575, 0.1192,
        1.01925, 1.9395, 0.11, 0.29605, 2.2698, 0.2315, 2.9898, 0.51655, 0.6621
    };
    public static final double DECAY = -0.5;
    /** 0.9^(1 / DECAY) - 1, so that R = 0.9 when t = S. */
    public static final double FACTOR = 19.0 / 81.0;
    public static final double DEFAULT_DESIRED_RETENTION = 0.9;
    public static final int DEFAULT_MAXIMUM_INTERVAL = 36500;
    /** Stability is kept within these bounds, in days, so the formulas stay finite. */
    static final double MIN_STABILITY = 0.01;
    static final double MAX_STABILITY = 36500;

    private final double[] w;
    private final double desiredRetention;
    private final int maximumInterval;

    /** The default parameters, 90% desired retention and a maximum interval of 100 years. */
    public Fsrs() {
        this(DEFAULT_DESIRED_RETENTION, DEFAULT_MAXIMUM_INTERVAL);
    }

    public Fsrs(double desiredRetention, int maximumInterval) {
        this(DEFAULT_PARAMETERS, desiredRetention, maximumInterval);
    }

    /**
     * @param parameters       the 19 weights w[0] to w[18]
     * @param desiredRetention the chance of recall intervals aim for, above 0 and below 1
     * @param maximumInterval  the longest interval in days, at least 1
     */
    public Fsrs(double[] parameters, double desiredRetention, int maximumInterval) {
        if (parameters == null || parameters.length != DEFAULT_PARAMETERS.length) {
            throw new IllegalArgumentException("FSRS-5 needs " + DEFAULT_PARAMETERS.length + " parameters");
        }
        if (!(desiredRetention > 0 && desiredRetention < 1)) {
            throw new IllegalArgumentException("Desired retention must be between 0 and 1: " + desiredRetention);
        }
        if (maximumInterval < 1) {
            throw new IllegalArgumentException("Maximum interval must be at least 1 day: " + maximumInterval);
        }
        this.w = parameters.clone();
        this.desiredRetention = desiredRetention;
        this.maximumInterval = maximumInterval;
    }

    /** The default parameters w[0] to w[18]. */
    public static double[] defaultParameters() {
        return DEFAULT_PARAMETERS.clone();
    }

    public double desiredRetention() {
        return desiredRetention;
    }

    public int maximumInterval() {
        return maximumInterval;
    }

    /** A card's memory: stability in days and difficulty from 1 to 10. */
    public record Memory(double stability, double difficulty) {
    }

    /** The chance of recall {@code elapsedDays} after the last review; 0 without a stability. */
    public static double retrievability(double elapsedDays, double stability) {
        if (!(stability > 0)) {
            return 0.0;
        }
        return Math.pow(1 + FACTOR * Math.max(0.0, elapsedDays) / stability, DECAY);
    }

    /** The days until recall drops to the desired retention, I = S / FACTOR * (r^(1 / DECAY) - 1), unrounded. */
    public double rawInterval(double stability) {
        return stability / FACTOR * (Math.pow(desiredRetention, 1 / DECAY) - 1);
    }

    /** {@link #rawInterval} rounded to whole days, from 1 to the maximum interval. */
    public int nextInterval(double stability) {
        long days = Math.round(rawInterval(stability));
        return (int) Math.min(maximumInterval, Math.max(1L, days));
    }

    /** S0(G) = w[G-1], at least 0.1 days. */
    public double initialStability(int grade) {
        checkGrade(grade);
        return Math.max(w[grade - 1], 0.1);
    }

    /** D0(G) = w4 - e^(w5 * (G - 1)) + 1, within 1 to 10. */
    public double initialDifficulty(int grade) {
        checkGrade(grade);
        return clampDifficulty(w[4] - Math.exp(w[5] * (grade - 1)) + 1);
    }

    /**
     * D' = D + dD * (10 - D) / 9 with dD = -w6 * (G - 3), then reverted towards D0(4) by w7, within
     * 1 to 10: Again and Hard make a card harder, Good leaves it, Easy makes it easier.
     */
    public double nextDifficulty(double difficulty, int grade) {
        checkGrade(grade);
        double delta = -w[6] * (grade - 3);
        double damped = difficulty + delta * (10 - difficulty) / 9;
        return clampDifficulty(w[7] * initialDifficulty(4) + (1 - w[7]) * damped);
    }

    /**
     * Stability after a successful recall (Hard, Good or Easy) at retrievability R:
     * S' = S * (e^w8 * (11 - D) * S^-w9 * (e^(w10 * (1 - R)) - 1) * hardPenalty * easyBonus + 1).
     * The lower R was, the more the review strengthens the memory.
     */
    public double recallStability(double difficulty, double stability, double retrievability, int grade) {
        checkGrade(grade);
        double hardPenalty = grade == 2 ? w[15] : 1.0;
        double easyBonus = grade == 4 ? w[16] : 1.0;
        return stability * (Math.exp(w[8]) * (11 - difficulty) * Math.pow(stability, -w[9])
            * (Math.exp(w[10] * (1 - retrievability)) - 1) * hardPenalty * easyBonus + 1);
    }

    /**
     * Stability after forgetting (Again): w11 * D^-w12 * ((S + 1)^w13 - 1) * e^(w14 * (1 - R)), and
     * never more than S / e^(w17 * w18), so a lapse always lowers it.
     */
    public double forgetStability(double difficulty, double stability, double retrievability) {
        double longTerm = w[11] * Math.pow(difficulty, -w[12]) * (Math.pow(stability + 1, w[13]) - 1)
            * Math.exp(w[14] * (1 - retrievability));
        double shortTerm = stability / Math.exp(w[17] * w[18]);
        return Math.min(longTerm, shortTerm);
    }

    /** Stability after a review on the same day as the last one: S' = S * e^(w17 * (G - 3 + w18)). */
    public double shortTermStability(double stability, int grade) {
        checkGrade(grade);
        return stability * Math.exp(w[17] * (grade - 3 + w[18]));
    }

    /**
     * The memory after a review with {@code grade}.
     *
     * @param before      the memory before, or null for a card's first review
     * @param elapsedDays whole days since the last review; under 1 means the same day
     */
    public Memory next(Memory before, int elapsedDays, int grade) {
        checkGrade(grade);
        if (before == null) {
            return new Memory(initialStability(grade), initialDifficulty(grade));
        }
        double stability = clampStability(before.stability());
        double difficulty = clampDifficulty(before.difficulty());
        double nextStability;
        if (elapsedDays < 1) {
            nextStability = shortTermStability(stability, grade);
        } else {
            double retrievability = retrievability(elapsedDays, stability);
            nextStability = grade == 1
                ? forgetStability(difficulty, stability, retrievability)
                : recallStability(difficulty, stability, retrievability, grade);
        }
        return new Memory(clampStability(nextStability), nextDifficulty(difficulty, grade));
    }

    private static double clampDifficulty(double difficulty) {
        return Math.min(10.0, Math.max(1.0, difficulty));
    }

    private static double clampStability(double stability) {
        return Math.min(MAX_STABILITY, Math.max(MIN_STABILITY, stability));
    }

    private static void checkGrade(int grade) {
        if (grade < 1 || grade > 4) {
            throw new IllegalArgumentException("FSRS grades are 1 to 4: " + grade);
        }
    }

    @Override
    public String toString() {
        return "Fsrs" + Arrays.toString(w) + " retention " + desiredRetention + ", maximum " + maximumInterval + " days";
    }
}
