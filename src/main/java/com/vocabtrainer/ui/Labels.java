package com.vocabtrainer.ui;

import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewRating;

import static com.vocabtrainer.util.Messages.tr;

/** The names the user sees for the review modes, ratings and card states; free of JavaFX. */
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
}
