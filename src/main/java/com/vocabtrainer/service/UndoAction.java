package com.vocabtrainer.service;

import com.vocabtrainer.domain.Achievement;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;

import java.util.List;

/**
 * Something done on the Review tab that {@link ReviewService#undoLast()} can take back, or took back.
 *
 * @param kind         what was done
 * @param word         the card as it was before, which undoing restores
 * @param answer       the answer submitted for the card then, kept again when it is undone so the
 *                     card can be rated without typing it again; null when none was submitted
 * @param questionMode how the card was asked: {@link ReviewMode#EN_TO_ZH}, {@link ReviewMode#ZH_TO_EN}
 *                     or {@link ReviewMode#CLOZE}
 * @param rating       the rating the user chose; null unless {@code kind} is {@link Kind#RATING}
 * @param xp           the XP the rating earned, its badges' rewards included, which undoing takes back
 * @param unlocked     the badges the rating unlocked, which undoing locks again
 * @param wasShown     whether the card was the one on screen, so undoing shows it again; false for a
 *                     leech suspended from the notice about it while another card was shown
 */
public record UndoAction(
    Kind kind,
    WordCard word,
    ReviewAnswer answer,
    ReviewMode questionMode,
    ReviewRating rating,
    int xp,
    List<Achievement> unlocked,
    boolean wasShown
) {
    public enum Kind {
        /** A saved rating: its review log, schedule change, XP, goal progress and badges. */
        RATING,
        /** A new card marked as already known. */
        KNOWN,
        /** A card suspended from the Review tab. */
        SUSPEND
    }

    public UndoAction {
        unlocked = List.copyOf(unlocked);
    }

    /** The same action with {@code restored} as the card. */
    UndoAction withWord(WordCard restored) {
        return new UndoAction(kind, restored, answer, questionMode, rating, xp, unlocked, wasShown);
    }
}
