package com.vocabtrainer.service;

import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.service.scheduling.CardScheduler;
import com.vocabtrainer.service.scheduling.Fsrs;
import com.vocabtrainer.service.scheduling.IntervalPreview;
import com.vocabtrainer.service.scheduling.SchedulingOptions;
import com.vocabtrainer.service.scheduling.StudyDay;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.logging.Logger;

/**
 * Schedules reviews with FSRS-5 (see {@link CardScheduler}) and picks the next card to show.
 *
 * <p>The rating the schedule uses is the user's rating, lowered when the typed answer was not
 * similar enough to the correct one: below 55% similarity it counts as Again, below 75% at most
 * Hard and below 90% at most Good. A card that lapses {@value WordCard#LEECH_LAPSES} times is
 * tagged {@value WordCard#LEECH_TAG}.
 */
public class ReviewScheduler {
    private static final Logger LOGGER = Logger.getLogger(ReviewScheduler.class.getName());

    private final CardScheduler cards;
    /** Replays review logs of older versions, which had no learning steps. */
    private final CardScheduler withoutSteps;
    private final WordSelector wordSelector;

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
        this.cards = new CardScheduler(options);
        this.withoutSteps = new CardScheduler(options.withoutSteps());
        this.wordSelector = new WordSelector(random);
    }

    public SchedulingOptions options() {
        return cards.options();
    }

    public StudyDay studyDay() {
        return cards.studyDay();
    }

    /** Applies a rating whose answer was fully correct; see {@link #applyRating(WordCard, ReviewRating, double, LocalDateTime)}. */
    public boolean applyRating(WordCard word, ReviewRating rating, LocalDateTime reviewedAt) {
        return applyRating(word, rating, 1.0, reviewedAt);
    }

    /**
     * Reschedules {@code word} for a review at {@code reviewedAt}. Returns whether this review made
     * the word a leech: it lapsed for the {@value WordCard#LEECH_LAPSES}th time, so it is now tagged
     * {@value WordCard#LEECH_TAG}.
     */
    public boolean applyRating(WordCard word, ReviewRating rating, double similarity, LocalDateTime reviewedAt) {
        return applyRating(cards, word, rating, similarity, reviewedAt);
    }

    private static boolean applyRating(CardScheduler scheduler, WordCard word, ReviewRating rating, double similarity,
                                       LocalDateTime reviewedAt) {
        scheduler.apply(word, effectiveRating(rating, similarity), reviewedAt);
        if (word.getLapses() >= WordCard.LEECH_LAPSES && !word.isLeech()) {
            word.addTag(WordCard.LEECH_TAG);
            LOGGER.info("\"" + word.getEnglish() + "\" lapsed " + word.getLapses() + " times and is tagged as a leech");
            return true;
        }
        return false;
    }

    /**
     * The interval each rating would give {@code word} at {@code now} for an answer of
     * {@code similarity}; nothing is changed.
     */
    public Map<ReviewRating, IntervalPreview> preview(WordCard word, double similarity, LocalDateTime now) {
        Map<ReviewRating, CardScheduler.Outcome> outcomes = cards.outcomes(word, now);
        Map<ReviewRating, IntervalPreview> previews = new EnumMap<>(ReviewRating.class);
        for (ReviewRating rating : ReviewRating.values()) {
            previews.put(rating, outcomes.get(effectiveRating(rating, similarity)).preview(now));
        }
        return previews;
    }

    /**
     * The rating the schedule uses: {@code rating}, but at most Again below 55% answer similarity,
     * Hard below 75% and Good below 90%.
     */
    public static ReviewRating effectiveRating(ReviewRating rating, double similarity) {
        double bounded = Double.isFinite(similarity) ? Math.max(0.0, Math.min(1.0, similarity)) : 0.0;
        int similarityGrade = bounded >= 0.9 ? 4 : bounded >= 0.75 ? 3 : bounded >= 0.55 ? 2 : 1;
        return ReviewRating.ofGrade(Math.min(rating.getGrade(), similarityGrade));
    }

    /**
     * Rebuilds the schedule of {@code word} from the review history an older version recorded: the
     * card starts new and every log, oldest first, is applied with the rating and answer similarity
     * it recorded. Those versions had no learning steps, so neither does the replay: every review
     * moves the card into review, as it did then, and a failed review is a lapse. FSRS stability and
     * difficulty do not depend on the steps. Counters (reviews, lapses, current run of correct
     * answers) are recounted; the text fields stay as they are.
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
            .sorted(Comparator.comparing(ReviewLog::getReviewedAt).thenComparingLong(ReviewLog::getId))
            .forEach(log -> applyRating(withoutSteps, word, log.getRating(), log.getSimilarity(), log.getReviewedAt()));
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
