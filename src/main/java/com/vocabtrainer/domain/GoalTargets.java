package com.vocabtrainer.domain;

import static com.vocabtrainer.util.Messages.tr;

/**
 * The daily goals a deck works towards: how many reviews and how many new words a study day should
 * have. A new word counts on the day of its first review, not when it is added or imported.
 *
 * @param reviewGoal  reviews per study day, from 0 to {@value #MAX_GOAL}
 * @param newWordGoal new words per study day, from 0 to {@value #MAX_GOAL}
 */
public record GoalTargets(int reviewGoal, int newWordGoal) {
    public static final int MAX_GOAL = 9999;

    public GoalTargets {
        if (reviewGoal < 0 || reviewGoal > MAX_GOAL) {
            throw new IllegalArgumentException(tr("validation.reviewGoal", MAX_GOAL));
        }
        if (newWordGoal < 0 || newWordGoal > MAX_GOAL) {
            throw new IllegalArgumentException(tr("validation.newWordGoal", MAX_GOAL));
        }
    }
}
