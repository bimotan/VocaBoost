package com.vocabtrainer.domain;

import java.time.LocalDateTime;

public class Deck {
    private long id;
    private String name;
    private LocalDateTime createdAt;
    private boolean archived;

    public Deck(long id, String name, LocalDateTime createdAt) {
        this(id, name, createdAt, false);
    }

    public Deck(long id, String name, LocalDateTime createdAt, boolean archived) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
        this.archived = archived;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    @Override
    public String toString() {
        return name;
    }
}
