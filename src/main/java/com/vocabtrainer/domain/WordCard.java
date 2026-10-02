package com.vocabtrainer.domain;

import java.time.LocalDateTime;

/**
 * A vocabulary card and its FSRS review state. The scheduling rules live in
 * {@code com.vocabtrainer.service.ReviewScheduler}; the rules here (due, weak, mastered) also exist
 * as SQL in {@code WordRepository}, and {@code WordPredicateAgreementTest} keeps the two in step.
 *
 * <p>The SM-2 fields of older versions (easiness factor, interval, consecutive correct answers) are
 * still written, so an older version of the app can open the database and older backups can be
 * restored: the interval is the scheduled interval in days and the easiness factor is read from the
 * difficulty.
 */
public class WordCard {
    public static final double DEFAULT_EASINESS = 2.5;
    /** A card in review with at least this stability (in days) is mastered; Anki calls such cards mature. */
    public static final double MASTERED_STABILITY_DAYS = 21.0;
    /** From this difficulty (1 to 10) on, a card that is not mastered yet counts as weak. */
    public static final double WEAK_DIFFICULTY = 7.0;
    /** A card rated Again within its last this-many reviews counts as weak. */
    public static final int RECENT_REVIEWS = 3;
    /** A card that lapsed this many times is tagged {@link #LEECH_TAG}. */
    public static final int LEECH_LAPSES = 8;
    public static final String LEECH_TAG = "leech";

    private long id;
    private long deckId;
    private String english;
    private String chinese;
    private String phonetic;
    private String partOfSpeech;
    private String exampleSentence;
    private String note;
    private String tags;
    private LocalDateTime addedAt;
    private LocalDateTime lastReviewedAt;
    private LocalDateTime nextReviewAt;
    private double easinessFactor;
    private int intervalDays;
    private int repetitions;
    private int consecutiveCorrect;
    private int lapses;
    private boolean archived;
    private CardState state = CardState.NEW;
    private double stability;
    private double difficulty;
    private int learningStep;

    public WordCard() {
    }

    public static WordCard createNew(long deckId, String english, String chinese) {
        LocalDateTime now = LocalDateTime.now();
        WordCard card = new WordCard();
        card.deckId = deckId;
        card.english = english == null ? "" : english.trim();
        card.chinese = chinese == null ? "" : chinese.trim();
        card.addedAt = now;
        card.nextReviewAt = now;
        card.easinessFactor = DEFAULT_EASINESS;
        return card;
    }

    /**
     * Whether the card is due. A learning or relearning card is due once its step time has come; any
     * other card from the start of the study day it is due on, that is when it is due before
     * {@code dayEnd}, the next day rollover after {@code now}. {@code WordRepository.findDue} and
     * {@code countDue} select the same cards in SQL.
     */
    public boolean isDue(LocalDateTime now, LocalDateTime dayEnd) {
        if (archived) {
            return false;
        }
        if (nextReviewAt == null) {
            return true;
        }
        return nextReviewAt.isBefore(dayEnd) && (!nextReviewAt.isAfter(now) || !state.isLearning());
    }

    /**
     * Mastered words: in review with a stability of at least {@value #MASTERED_STABILITY_DAYS} days,
     * whatever lapses they had before. {@code WordRepository.countMastered} counts the same in SQL.
     */
    public boolean isMastered() {
        return state == CardState.REVIEW && stability >= MASTERED_STABILITY_DAYS;
    }

    /**
     * Weak words: relearning after a lapse, rated Again within the last {@value #RECENT_REVIEWS}
     * reviews, or hard (difficulty {@value #WEAK_DIFFICULTY} or more) and not mastered yet. A lapse
     * stops counting once the word was recalled {@value #RECENT_REVIEWS} times in a row, and a
     * mastered word is never weak. The weak-words review mode ({@code WordRepository.findWeak})
     * selects the same words in SQL.
     */
    public boolean isWeak() {
        return state == CardState.RELEARNING
            || failedRecently()
            || (difficulty >= WEAK_DIFFICULTY && !isMastered());
    }

    /**
     * Rated Again within the last {@value #RECENT_REVIEWS} reviews: some review was not part of the
     * current run of correct answers, and that run is shorter than {@value #RECENT_REVIEWS}.
     */
    public boolean failedRecently() {
        return repetitions > consecutiveCorrect && consecutiveCorrect < RECENT_REVIEWS;
    }

    /** Tagged {@link #LEECH_TAG}: lapsed {@value #LEECH_LAPSES} times or more. */
    public boolean isLeech() {
        return hasTag(LEECH_TAG);
    }

    /** Whether one of the tags (separated by ";" or ",") is {@code tag}, ignoring case. */
    public boolean hasTag(String tag) {
        if (tags == null || tag == null || tag.isBlank()) {
            return false;
        }
        for (String part : tags.split("[;,]")) {
            if (part.trim().equalsIgnoreCase(tag.trim())) {
                return true;
            }
        }
        return false;
    }

    /** Appends {@code tag} after "; " unless the card already has it. */
    public void addTag(String tag) {
        if (tag == null || tag.isBlank() || hasTag(tag)) {
            return;
        }
        String clean = tags == null ? "" : tags.trim();
        tags = clean.isEmpty() ? tag.trim() : clean + "; " + tag.trim();
    }

    /**
     * Fills the FSRS state of a card that has only the SM-2 schedule of older versions and no review
     * history to replay: a card never reviewed is NEW; any other is in REVIEW with a stability of its
     * interval (at least half a day) and a difficulty read from its easiness factor.
     */
    public void estimateStateFromLegacySchedule() {
        learningStep = 0;
        if (repetitions <= 0 && lastReviewedAt == null) {
            state = CardState.NEW;
            stability = 0;
            difficulty = 0;
            return;
        }
        state = CardState.REVIEW;
        stability = Math.max(intervalDays, 0.5);
        difficulty = difficultyFromEasiness(easinessFactor);
    }

    /** The FSRS difficulty an SM-2 easiness factor stands for: 2.5 gives 5, 1.3 gives 9, within 1 to 10. */
    public static double difficultyFromEasiness(double easinessFactor) {
        double ease = Double.isFinite(easinessFactor) && easinessFactor > 0 ? easinessFactor : DEFAULT_EASINESS;
        return Math.min(10.0, Math.max(1.0, 5.0 + (DEFAULT_EASINESS - ease) / 0.3));
    }

    /** The SM-2 easiness factor written for older versions: the inverse of {@link #difficultyFromEasiness}, 1.3 to 2.8. */
    public static double easinessFromDifficulty(double difficulty) {
        return Math.min(2.8, Math.max(1.3, DEFAULT_EASINESS - (difficulty - 5.0) * 0.3));
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getDeckId() {
        return deckId;
    }

    public void setDeckId(long deckId) {
        this.deckId = deckId;
    }

    public String getEnglish() {
        return english;
    }

    public void setEnglish(String english) {
        this.english = english;
    }

    public String getChinese() {
        return chinese;
    }

    public void setChinese(String chinese) {
        this.chinese = chinese;
    }

    public String getPhonetic() {
        return phonetic;
    }

    public void setPhonetic(String phonetic) {
        this.phonetic = phonetic;
    }

    public String getPartOfSpeech() {
        return partOfSpeech;
    }

    public void setPartOfSpeech(String partOfSpeech) {
        this.partOfSpeech = partOfSpeech;
    }

    public String getExampleSentence() {
        return exampleSentence;
    }

    public void setExampleSentence(String exampleSentence) {
        this.exampleSentence = exampleSentence;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public LocalDateTime getAddedAt() {
        return addedAt;
    }

    public void setAddedAt(LocalDateTime addedAt) {
        this.addedAt = addedAt;
    }

    public LocalDateTime getLastReviewedAt() {
        return lastReviewedAt;
    }

    public void setLastReviewedAt(LocalDateTime lastReviewedAt) {
        this.lastReviewedAt = lastReviewedAt;
    }

    public LocalDateTime getNextReviewAt() {
        return nextReviewAt;
    }

    public void setNextReviewAt(LocalDateTime nextReviewAt) {
        this.nextReviewAt = nextReviewAt;
    }

    public double getEasinessFactor() {
        return easinessFactor;
    }

    public void setEasinessFactor(double easinessFactor) {
        this.easinessFactor = easinessFactor;
    }

    public int getIntervalDays() {
        return intervalDays;
    }

    public void setIntervalDays(int intervalDays) {
        this.intervalDays = intervalDays;
    }

    public int getRepetitions() {
        return repetitions;
    }

    public void setRepetitions(int repetitions) {
        this.repetitions = repetitions;
    }

    public int getConsecutiveCorrect() {
        return consecutiveCorrect;
    }

    public void setConsecutiveCorrect(int consecutiveCorrect) {
        this.consecutiveCorrect = consecutiveCorrect;
    }

    public int getLapses() {
        return lapses;
    }

    public void setLapses(int lapses) {
        this.lapses = lapses;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    public CardState getState() {
        return state;
    }

    public void setState(CardState state) {
        this.state = state == null ? CardState.NEW : state;
    }

    /** FSRS stability in days: the interval after which recall drops to 90%; 0 for a new card. */
    public double getStability() {
        return stability;
    }

    public void setStability(double stability) {
        this.stability = stability;
    }

    /** FSRS difficulty from 1 (easy) to 10 (hard); 0 for a new card. */
    public double getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(double difficulty) {
        this.difficulty = difficulty;
    }

    /** The index of the current learning or relearning step. */
    public int getLearningStep() {
        return learningStep;
    }

    public void setLearningStep(int learningStep) {
        this.learningStep = learningStep;
    }
}
