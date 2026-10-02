package com.vocabtrainer.service;

import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.ReviewKind;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.service.scheduling.CardScheduler;
import com.vocabtrainer.service.scheduling.Fsrs;
import com.vocabtrainer.service.scheduling.IntervalPreview;
import com.vocabtrainer.service.scheduling.SchedulingOptions;
import com.vocabtrainer.service.scheduling.StudyDay;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Schedules reviews with FSRS-5 (see {@link CardScheduler}) and picks the next card to show.
 *
 * <p>The rating the schedule uses is the user's rating, lowered when the typed answer did not
 * count as that good (see {@link AnswerGrader}); {@code ReviewService} works it out and passes it
 * in. Logs of older versions recorded only the user's rating and the answer similarity: below 55%
 * similarity it counts as Again, below 75% at most Hard and below 90% at most Good
 * ({@link #effectiveRating}). A card that lapses {@value WordCard#LEECH_LAPSES} times is tagged
 * {@value WordCard#LEECH_TAG}.
 *
 * <p>When the word's deck has an exam date ({@link ExamDates}), a review that would fall on or after
 * it is brought forward into the last days before it ({@link com.vocabtrainer.service.scheduling.ExamClamp});
 * the rating previews show the shortened interval. Replaying old review logs ignores the exam.
 */
public class ReviewScheduler {
    /**
     * The stability, in days, of a new word the user marks as already known: at the default desired
     * retention it is due again in about two months, and then reviewed like any other word.
     */
    public static final double KNOWN_STABILITY_DAYS = 60.0;

    private static final Logger LOGGER = Logger.getLogger(ReviewScheduler.class.getName());

    /** The exam date of each deck, if it has one. */
    @FunctionalInterface
    public interface ExamDates {
        /** No deck has an exam date. */
        ExamDates NONE = deckId -> Optional.empty();

        Optional<LocalDate> examDate(long deckId);
    }

    private final CardScheduler cards;
    /** Replays review logs of older versions, which had no learning steps. */
    private final CardScheduler withoutSteps;
    private final WordSelector wordSelector;
    private final ExamDates examDates;

    public ReviewScheduler() {
        this(SchedulingOptions.defaults(), new Random());
    }

    public ReviewScheduler(Random random) {
        this(SchedulingOptions.defaults(), random);
    }

    public ReviewScheduler(SchedulingOptions options) {
        this(options, new Random());
    }

    /** @param random picks among due cards; scheduling itself is deterministic */
    public ReviewScheduler(SchedulingOptions options, Random random) {
        this(options, random, ExamDates.NONE);
    }

    /**
     * @param random    picks among due cards; scheduling itself is deterministic
     * @param examDates the exam date of each deck, which reviews are kept before; read at every rating
     */
    public ReviewScheduler(SchedulingOptions options, Random random, ExamDates examDates) {
        this.cards = new CardScheduler(options);
        this.withoutSteps = new CardScheduler(options.withoutSteps());
        this.wordSelector = new WordSelector(random);
        this.examDates = examDates == null ? ExamDates.NONE : examDates;
    }

    public SchedulingOptions options() {
        return cards.options();
    }

    public StudyDay studyDay() {
        return cards.studyDay();
    }

    /**
     * Reschedules {@code word} for a review at {@code reviewedAt} that counts as {@code rating}, the
     * rating after the answer check. Returns whether this review made the word a leech: it lapsed
     * for the {@value WordCard#LEECH_LAPSES}th time, so it is now tagged {@value WordCard#LEECH_TAG}.
     */
    public boolean applyRating(WordCard word, ReviewRating rating, LocalDateTime reviewedAt) {
        return applyRating(cards, word, rating, reviewedAt, examDate(word));
    }

    /**
     * Reschedules {@code word} for a review rated {@code rating} whose answer had {@code similarity},
     * capped like a log of an older version ({@link #effectiveRating}); see
     * {@link #applyRating(WordCard, ReviewRating, LocalDateTime)}.
     */
    public boolean applyRating(WordCard word, ReviewRating rating, double similarity, LocalDateTime reviewedAt) {
        return applyRating(cards, word, effectiveRating(rating, similarity), reviewedAt, examDate(word));
    }

    private static boolean applyRating(CardScheduler scheduler, WordCard word, ReviewRating effectiveRating,
                                       LocalDateTime reviewedAt, LocalDate examDate) {
        scheduler.apply(word, effectiveRating, reviewedAt, examDate);
        return tagIfLeech(word);
    }

    /**
     * Puts {@code word}, which the user already knows, straight into review at {@code at}, with
     * {@value #KNOWN_STABILITY_DAYS} days of stability, and due before its deck's exam when that
     * interval would reach it; see {@link CardScheduler#markKnown}.
     */
    public void markKnown(WordCard word, LocalDateTime at) {
        cards.markKnown(word, KNOWN_STABILITY_DAYS, at, examDate(word));
    }

    /**
     * When {@code word}, a review card due on or after the exam on {@code examDate}, should be due
     * instead so it is reviewed in the last days before the exam; empty when it can stay. See
     * {@link CardScheduler#dueBeforeExam}.
     */
    public Optional<LocalDateTime> dueBeforeExam(WordCard word, LocalDateTime now, LocalDate examDate) {
        return cards.dueBeforeExam(word, now, examDate);
    }

    /** The exam date of the word's deck; null when it has none or it cannot be read, which only logs. */
    private LocalDate examDate(WordCard word) {
        try {
            return examDates.examDate(word.getDeckId()).orElse(null);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Cannot read the exam date of deck " + word.getDeckId()
                + "; scheduling without it", e);
            return null;
        }
    }

    /**
     * Tags {@code word} {@value WordCard#LEECH_TAG} (and logs it) if it lapsed
     * {@value WordCard#LEECH_LAPSES} times or more and is not tagged yet; returns whether it was
     * tagged now.
     */
    public static boolean tagIfLeech(WordCard word) {
        if (word.getLapses() >= WordCard.LEECH_LAPSES && !word.isLeech()) {
            word.addTag(WordCard.LEECH_TAG);
            LOGGER.info("\"" + word.getEnglish() + "\" lapsed " + word.getLapses() + " times and is tagged as a leech");
            return true;
        }
        return false;
    }

    /**
     * The interval a review of {@code word} at {@code now} would give when it counts as each
     * rating; nothing is changed.
     */
    public Map<ReviewRating, IntervalPreview> intervals(WordCard word, LocalDateTime now) {
        Map<ReviewRating, CardScheduler.Outcome> outcomes = cards.outcomes(word, now, examDate(word));
        Map<ReviewRating, IntervalPreview> intervals = new EnumMap<>(ReviewRating.class);
        for (ReviewRating rating : ReviewRating.values()) {
            intervals.put(rating, outcomes.get(rating).preview(now));
        }
        return intervals;
    }

    /**
     * The interval each rating would give {@code word} at {@code now} for an answer of
     * {@code similarity}, capped as by {@link #effectiveRating}; nothing is changed.
     */
    public Map<ReviewRating, IntervalPreview> preview(WordCard word, double similarity, LocalDateTime now) {
        Map<ReviewRating, IntervalPreview> intervals = intervals(word, now);
        Map<ReviewRating, IntervalPreview> previews = new EnumMap<>(ReviewRating.class);
        for (ReviewRating rating : ReviewRating.values()) {
            previews.put(rating, intervals.get(effectiveRating(rating, similarity)));
        }
        return previews;
    }

    /**
     * The rating a log of an older version counts as: {@code rating}, but at most Again below 55%
     * answer similarity, Hard below 75% and Good below 90% ({@link ReviewRating#maxForSimilarity}).
     */
    public static ReviewRating effectiveRating(ReviewRating rating, double similarity) {
        return rating.atMost(ReviewRating.maxForSimilarity(similarity));
    }

    /**
     * Rebuilds the schedule of {@code word} from the review history an older version recorded: the
     * card starts new and every log, oldest first, is applied with the rating and answer similarity
     * it recorded. Those versions had no learning steps, so neither does the replay: every review
     * moves the card into review, as it did then, and a failed review is a lapse. FSRS stability and
     * difficulty do not depend on the steps. Each log counts as its {@link ReviewLog#getEffectiveRating()
     * effective rating}. Counters (reviews, lapses, current run of correct answers) are recounted; the
     * text fields stay as they are. Practice logs are skipped: practicing a card that was not due did
     * not change its schedule. A {@link ReviewKind#KNOWN} log puts the card into review as
     * {@link #markKnown} did.
     */
    public void replay(WordCard word, List<ReviewLog> history) {
        word.setState(CardState.NEW);
        word.setStability(0);
        word.setDifficulty(0);
        word.setLearningStep(0);
        word.setIntervalDays(0);
        word.setRepetitions(0);
        word.setConsecutiveCorrect(0);
        word.setLapses(0);
        word.setLastReviewedAt(null);
        word.setEasinessFactor(WordCard.DEFAULT_EASINESS);
        history.stream()
            .filter(log -> log.getKind() != ReviewKind.PRACTICE)
            .sorted(Comparator.comparing(ReviewLog::getReviewedAt).thenComparingLong(ReviewLog::getId))
            .forEach(log -> {
                if (log.getKind() == ReviewKind.KNOWN) {
                    withoutSteps.markKnown(word, KNOWN_STABILITY_DAYS, log.getReviewedAt());
                } else {
                    applyRating(withoutSteps, word, log.getEffectiveRating(), log.getReviewedAt(), null);
                }
            });
    }

    /**
     * The chance that {@code word} is recalled at {@code now} (FSRS retrievability); empty for a
     * word never reviewed.
     */
    public static OptionalDouble retrievability(WordCard word, LocalDateTime now) {
        if (word.getState() == CardState.NEW || !(word.getStability() > 0) || word.getLastReviewedAt() == null) {
            return OptionalDouble.empty();
        }
        double elapsedDays = Math.max(0L, Duration.between(word.getLastReviewedAt(), now).toSeconds()) / 86_400.0;
        return OptionalDouble.of(Fsrs.retrievability(elapsedDays, word.getStability()));
    }

    public Optional<WordCard> selectNext(List<WordCard> dueWords, LocalDateTime now) {
        return wordSelector.selectNext(dueWords, now);
    }
}
