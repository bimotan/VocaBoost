package com.vocabtrainer.ui;

import com.vocabtrainer.domain.Achievement;
import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewRating;

import static com.vocabtrainer.util.Messages.tr;

/**
 * The names the user sees for the review modes (which are also the question directions), ratings,
 * card states and badges; free of JavaFX. The enums and the badge codes are identifiers stored in the
 * database and the backups, never shown as they are.
 */
public final class Labels {
    private Labels() {
    }

    /** The name of a review mode, as the mode selector and the question line show it. */
    public static String mode(ReviewMode mode) {
        return switch (mode) {
            case EN_TO_ZH -> tr("mode.enToZh");
            case ZH_TO_EN -> tr("mode.zhToEn");
            case MIXED -> tr("mode.mixed");
            case WEAK_WORDS -> tr("mode.weakWords");
            case CLOZE -> tr("mode.cloze");
        };
    }

    /** What the answer field asks for when a question is asked this way. */
    public static String modePrompt(ReviewMode mode) {
        return switch (mode) {
            case EN_TO_ZH -> tr("mode.enToZh.prompt");
            case ZH_TO_EN -> tr("mode.zhToEn.prompt");
            case MIXED -> tr("mode.mixed.prompt");
            case WEAK_WORDS -> tr("mode.weakWords.prompt");
            case CLOZE -> tr("mode.cloze.prompt");
        };
    }

    /** The name of a rating: Again, Hard, Good or Easy. */
    public static String rating(ReviewRating rating) {
        return switch (rating) {
            case AGAIN -> tr("rating.again");
            case HARD -> tr("rating.hard");
            case GOOD -> tr("rating.good");
            case EASY -> tr("rating.easy");
        };
    }

    /** The name of a card state: New, Learning, Review or Relearning. */
    public static String cardState(CardState state) {
        return switch (state) {
            case NEW -> tr("cardState.new");
            case LEARNING -> tr("cardState.learning");
            case REVIEW -> tr("cardState.review");
            case RELEARNING -> tr("cardState.relearning");
        };
    }

    /**
     * The name of a badge in the app's language. The database keeps the English name the badge was
     * unlocked with; a badge this version does not know (one a newer version unlocked) shows that name.
     */
    public static String achievement(Achievement achievement) {
        return switch (achievement.code()) {
            case "first_review" -> tr("achievement.firstReview");
            case "review_10" -> tr("achievement.reviews", 10);
            case "review_50" -> tr("achievement.reviews", 50);
            case "review_100" -> tr("achievement.reviews", 100);
            case "streak_3" -> tr("achievement.streak", 3);
            case "streak_7" -> tr("achievement.streak", 7);
            case "streak_30" -> tr("achievement.streak", 30);
            case "daily_goal" -> tr("achievement.dailyGoal");
            case "overdue_rescue" -> tr("achievement.overdueRescue");
            default -> achievement.name();
        };
    }
}
