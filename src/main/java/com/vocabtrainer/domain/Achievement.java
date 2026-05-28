package com.vocabtrainer.domain;

import java.time.LocalDateTime;

public record Achievement(
    String code,
    String name,
    String description,
    LocalDateTime unlockedAt,
    int xpReward
) {
}
