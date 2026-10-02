package com.vocabtrainer.service;

import com.vocabtrainer.repository.GoalRepository;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.WordRepository;

import java.sql.SQLException;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Imports the bundled GRE starter words once per database. The {@code starter.imported} setting
 * records that the decision was made, so emptying, renaming or archiving a deck later never
 * brings the starter words back.
 */
public class StarterImportService {
    private static final Logger LOGGER = Logger.getLogger(StarterImportService.class.getName());

    private final ImportExportService importExportService;
    private final WordRepository wordRepository;
    private final ReviewLogRepository reviewLogRepository;
    private final GoalRepository goalRepository;
    private final SettingsService settingsService;

    public StarterImportService(ImportExportService importExportService, WordRepository wordRepository,
                                ReviewLogRepository reviewLogRepository, GoalRepository goalRepository,
                                SettingsService settingsService) {
        this.importExportService = importExportService;
        this.wordRepository = wordRepository;
        this.reviewLogRepository = reviewLogRepository;
        this.goalRepository = goalRepository;
        this.settingsService = settingsService;
    }

    /**
     * Imports the starter words into {@code deckId} if this is a brand-new database. A database
     * created before the flag existed is only marked as decided when it already holds words,
     * review logs or goal history in any deck.
     */
    public Optional<ImportResult> importOnce(long deckId) {
        if (settingsService.isStarterImported()) {
            return Optional.empty();
        }
        if (hasStudyData()) {
            settingsService.markStarterImported();
            return Optional.empty();
        }
        ImportResult result = importExportService.importBundledGreStarter(deckId);
        settingsService.markStarterImported();
        LOGGER.info("Imported " + result.importedCount() + " bundled starter words into deck " + deckId);
        return Optional.of(result);
    }

    private boolean hasStudyData() {
        try {
            // Deleting words also deletes their review logs, so goal history is checked as well.
            return wordRepository.countAllInDatabase() > 0
                || reviewLogRepository.countAll() > 0
                || goalRepository.countAll() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot check whether the database already has words", e);
        }
    }
}
