package com.vocabtrainer.domain;

import java.time.LocalDate;

/**
 * One study day of the workload forecast.
 *
 * @param date     the study day
 * @param reviews  learning, relearning and review cards due that day (for today also the overdue ones)
 * @param newWords new words the deck's new-cards-per-day limit lets in that day, while there are any
 */
public record WorkloadDay(LocalDate date, int reviews, int newWords) {
}
