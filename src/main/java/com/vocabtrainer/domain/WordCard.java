package com.vocabtrainer.domain;

import java.time.Duration;
import java.time.LocalDateTime;

public class WordCard {
    public static final double DEFAULT_EASINESS = 2.5;

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

    public double calculateMemoryStrength(LocalDateTime now) {
        LocalDateTime baseline = lastReviewedAt == null ? addedAt : lastReviewedAt;
        if (baseline == null) {
            return 0.0;
        }
        long hoursSinceReview = Math.max(0, Duration.between(baseline, now).toHours());
        double stability = 5.0 * easinessFactor * (1 + consecutiveCorrect / 10.0);
        if (stability <= 0) {
            return 0.0;
        }
        return Math.exp(-hoursSinceReview / stability);
    }

    public boolean isDue(LocalDateTime now) {
        return !archived && (nextReviewAt == null || !nextReviewAt.isAfter(now));
    }

    /** Mastered words; {@code WordRepository.countMastered} counts the same in SQL. */
    public boolean isMastered() {
        return consecutiveCorrect >= 3 && intervalDays >= 7 && lapses == 0;
    }

    /**
     * Weak words: lapsed, not yet recalled three times in a row, or on a short interval. The
     * weak-words review mode ({@code WordRepository.findWeak}) selects the same words in SQL.
     */
    public boolean isWeak() {
        return lapses > 0 || consecutiveCorrect < 3 || intervalDays <= 3;
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
