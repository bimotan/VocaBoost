package com.vocabtrainer.service;

import com.vocabtrainer.domain.GoalTargets;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.SettingsRepository;
import com.vocabtrainer.repository.TestDatabases;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The goals are user settings: default goals for every deck, a deck's own goals, and the session goal (G5). */
class GoalSettingsTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    @Test
    void goalsAreSavedAndADeckCanHaveItsOwn() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("goal-settings.db"));
        GoalSettings settings = settings(databaseManager);
        assertEquals(new GoalTargets(GoalService.DEFAULT_REVIEW_GOAL, GoalService.DEFAULT_NEW_WORD_GOAL),
            settings.goalsFor(1));
        assertEquals(ReviewSettings.DEFAULT_SESSION_SIZE, settings.sessionGoal());

        settings.saveDefaults(new GoalTargets(50, 15));
        settings.saveDeckGoals(2, new GoalTargets(10, 0));
        settings.saveSessionGoal(35);

        GoalSettings reopened = settings(databaseManager);
        assertEquals(new GoalTargets(50, 15), reopened.goalsFor(1));
        assertEquals(new GoalTargets(10, 0), reopened.goalsFor(2));
        assertEquals(Optional.empty(), reopened.deckGoals(1));
        assertEquals(35, reopened.sessionGoal());
        assertEquals(35, new ReviewSettings(new SettingsService(new SettingsRepository(databaseManager))).sessionSize(),
            "the session goal is the review session size");

        reopened.clearDeckGoals(2);
        assertEquals(new GoalTargets(50, 15), settings(databaseManager).goalsFor(2));
    }

    @Test
    void invalidSavedGoalsAreIgnoredAndInvalidGoalsAreRefused() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("invalid-goals.db"));
        SettingsService settingsService = new SettingsService(new SettingsRepository(databaseManager));
        settingsService.save(GoalSettings.REVIEW_GOAL_KEY, "lots");
        settingsService.save(GoalSettings.NEW_WORD_GOAL_KEY, "5");
        settingsService.save(GoalSettings.REVIEW_GOAL_KEY + ".3", "-1");
        GoalSettings settings = settings(databaseManager);

        assertEquals(new GoalTargets(GoalService.DEFAULT_REVIEW_GOAL, GoalService.DEFAULT_NEW_WORD_GOAL),
            settings.defaults());
        assertEquals(Optional.empty(), settings.deckGoals(3));
        assertThrows(IllegalArgumentException.class, () -> new GoalTargets(10000, 5));
        assertThrows(IllegalArgumentException.class, () -> settings.saveSessionGoal(501));
    }

    private static GoalSettings settings(DatabaseManager databaseManager) {
        SettingsService settings = new SettingsService(new SettingsRepository(databaseManager));
        return new GoalSettings(settings, new ReviewSettings(settings));
    }
}
