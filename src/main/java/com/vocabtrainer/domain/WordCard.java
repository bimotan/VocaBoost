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

    public boolean isMastered() {
        return consecutiveCorrect >= 3 && intervalDays >= 7 && lapses == 0;
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
}