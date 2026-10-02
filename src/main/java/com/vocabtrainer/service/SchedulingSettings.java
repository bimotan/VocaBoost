package com.vocabtrainer.service;

import com.vocabtrainer.service.scheduling.SchedulingOptions;

/**
 * The scheduler settings the user can change while the app runs: the desired retention
 * ({@value SettingsService#DESIRED_RETENTION_KEY}) and the hour a study day starts
 * ({@value SettingsService#DAY_ROLLOVER_HOUR_KEY}). Saving one stores it in the {@code settings}
 * table and applies it to the running {@link ReviewScheduler} at once, so the next rating, interval
 * preview and due count use it; the next start reads it with {@link SettingsService#getSchedulingOptions()}.
 * Cards keep the due dates they have: a new retention changes the intervals of the reviews to come.
 */
public class SchedulingSettings {
    private final SettingsService settings;
    private final ReviewScheduler scheduler;

    public SchedulingSettings(SettingsService settings, ReviewScheduler scheduler) {
        this.settings = settings;
        this.scheduler = scheduler;
    }

    /** The options the scheduler uses now. */
    public SchedulingOptions options() {
        return scheduler.options();
    }

    /**
     * Saves and applies the chance of recall review intervals aim for. A higher retention means
     * shorter intervals: fewer words are forgotten, but there are more reviews.
     *
     * @throws IllegalArgumentException if {@code retention} is not from
     *                                  {@value SchedulingOptions#MIN_DESIRED_RETENTION} to
     *                                  {@value SchedulingOptions#MAX_DESIRED_RETENTION}; nothing is saved
     */
    public void saveDesiredRetention(double retention) {
        SchedulingOptions changed = scheduler.options().withDesiredRetention(retention);
        settings.save(SettingsService.DESIRED_RETENTION_KEY, String.valueOf(retention));
        scheduler.setOptions(changed);
    }

    /**
     * Saves and applies the hour (0 to 23) at which a new study day starts, which decides what is due
     * today and which day a review counts for.
     *
     * @throws IllegalArgumentException if {@code hour} is not from 0 to 23; nothing is saved
     */
    public void saveDayRolloverHour(int hour) {
        SchedulingOptions changed = scheduler.options().withDayRolloverHour(hour);
        settings.save(SettingsService.DAY_ROLLOVER_HOUR_KEY, String.valueOf(hour));
        scheduler.setOptions(changed);
    }
}
