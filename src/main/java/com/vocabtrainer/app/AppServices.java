package com.vocabtrainer.app;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.repository.AchievementRepository;
import com.vocabtrainer.repository.AiCacheRepository;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.DictionaryCacheRepository;
import com.vocabtrainer.repository.GoalRepository;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.SettingsRepository;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.AchievementService;
import com.vocabtrainer.service.AiService;
import com.vocabtrainer.service.AiServiceFactory;
import com.vocabtrainer.service.BackupService;
import com.vocabtrainer.service.DeckService;
import com.vocabtrainer.service.DictionaryService;
import com.vocabtrainer.service.DictionaryServiceFactory;
import com.vocabtrainer.service.GoalService;
import com.vocabtrainer.service.ImportExportService;
import com.vocabtrainer.service.ReviewScheduler;
import com.vocabtrainer.service.ReviewService;
import com.vocabtrainer.service.SettingsService;
import com.vocabtrainer.service.SimilarityService;
import com.vocabtrainer.service.StarterImportService;
import com.vocabtrainer.service.StatsService;
import com.vocabtrainer.service.WordValidationService;
import com.vocabtrainer.ui.Dialogs;
import com.vocabtrainer.ui.MainWindow;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The repositories and services of one database, wired the way the desktop app runs them.
 * {@link VocabTrainerApp} and the UI tests both build the app through {@link #builder(Path)}, so
 * the tests exercise the same wiring as the real app.
 *
 * @param dictionaryServices builds the dictionary service from the saved settings; called again
 *                           when the dictionary settings change
 * @param aiServices         builds the AI service from the saved settings; called again when the
 *                           AI settings change
 * @param startupDeck        the deck the main window opens on
 */
public record AppServices(
    DatabaseManager databaseManager,
    DeckRepository deckRepository,
    WordRepository wordRepository,
    ReviewLogRepository reviewLogRepository,
    GoalRepository goalRepository,
    AchievementRepository achievementRepository,
    DictionaryCacheRepository dictionaryCacheRepository,
    SettingsRepository settingsRepository,
    AiCacheRepository aiCacheRepository,
    SettingsService settingsService,
    DeckService deckService,
    WordValidationService validationService,
    ImportExportService importExportService,
    GoalService goalService,
    AchievementService achievementService,
    ReviewService reviewService,
    StatsService statsService,
    BackupService backupService,
    Supplier<DictionaryService> dictionaryServices,
    Supplier<AiService> aiServices,
    Deck startupDeck
) {
    public static Builder builder(Path databasePath) {
        return new Builder(databasePath);
    }

    /** The main window on these services; {@code dialogs} shows its modal dialogs and file choosers. */
    public MainWindow createMainWindow(Dialogs dialogs) {
        return new MainWindow(this, dialogs);
    }

    /**
     * Opens one database. The app uses the defaults; tests replace the network-backed dictionary
     * and AI services, or a repository, before calling {@link #open()}.
     */
    public static final class Builder {
        private final Path databasePath;
        private Function<DatabaseManager, WordRepository> wordRepositoryFactory = WordRepository::new;
        private Function<DatabaseManager, ReviewLogRepository> reviewLogRepositoryFactory = ReviewLogRepository::new;
        private BiFunction<DictionaryCacheRepository, SettingsService, DictionaryService> dictionaryServiceFactory =
            DictionaryServiceFactory::create;
        private BiFunction<AiCacheRepository, SettingsService, AiService> aiServiceFactory = AiServiceFactory::create;

        private Builder(Path databasePath) {
            this.databasePath = Objects.requireNonNull(databasePath, "databasePath");
        }

        public Builder wordRepository(Function<DatabaseManager, WordRepository> factory) {
            this.wordRepositoryFactory = Objects.requireNonNull(factory);
            return this;
        }

        public Builder reviewLogRepository(Function<DatabaseManager, ReviewLogRepository> factory) {
            this.reviewLogRepositoryFactory = Objects.requireNonNull(factory);
            return this;
        }

        public Builder dictionaryService(BiFunction<DictionaryCacheRepository, SettingsService, DictionaryService> factory) {
            this.dictionaryServiceFactory = Objects.requireNonNull(factory);
            return this;
        }

        public Builder aiService(BiFunction<AiCacheRepository, SettingsService, AiService> factory) {
            this.aiServiceFactory = Objects.requireNonNull(factory);
            return this;
        }

        /**
         * Creates or migrates the database, resolves the startup deck, imports the starter words into
         * a brand-new database and wires the services.
         */
        public AppServices open() throws SQLException {
            DatabaseManager databaseManager = new DatabaseManager(databasePath);
            databaseManager.initialize();

            DeckRepository deckRepository = new DeckRepository(databaseManager);
            WordRepository wordRepository = wordRepositoryFactory.apply(databaseManager);
            ReviewLogRepository reviewLogRepository = reviewLogRepositoryFactory.apply(databaseManager);
            GoalRepository goalRepository = new GoalRepository(databaseManager);
            AchievementRepository achievementRepository = new AchievementRepository(databaseManager);
            DictionaryCacheRepository dictionaryCacheRepository = new DictionaryCacheRepository(databaseManager);
            SettingsRepository settingsRepository = new SettingsRepository(databaseManager);
            AiCacheRepository aiCacheRepository = new AiCacheRepository(databaseManager);
            SettingsService settingsService = new SettingsService(settingsRepository);
            DeckService deckService = new DeckService(deckRepository, settingsService);
            Deck startupDeck = deckService.resolveStartupDeck();

            SimilarityService similarityService = new SimilarityService();
            ReviewScheduler reviewScheduler = new ReviewScheduler();
            GoalService goalService = new GoalService(goalRepository);
            AchievementService achievementService = new AchievementService(achievementRepository, goalService);
            WordValidationService validationService = new WordValidationService();
            ImportExportService importExportService = new ImportExportService(wordRepository, validationService);
            StarterImportService starterImportService = new StarterImportService(
                importExportService, wordRepository, reviewLogRepository, goalRepository, settingsService);
            starterImportService.importOnce(startupDeck.getId());
            ReviewService reviewService = new ReviewService(
                wordRepository,
                reviewLogRepository,
                similarityService,
                reviewScheduler,
                goalService,
                achievementService
            );
            StatsService statsService = new StatsService(wordRepository, reviewLogRepository, databaseManager);
            BackupService backupService = new BackupService(deckRepository, wordRepository, reviewLogRepository,
                goalRepository, achievementRepository, databaseManager, validationService);
            BiFunction<DictionaryCacheRepository, SettingsService, DictionaryService> dictionaryFactory =
                dictionaryServiceFactory;
            BiFunction<AiCacheRepository, SettingsService, AiService> aiFactory = aiServiceFactory;

            return new AppServices(
                databaseManager,
                deckRepository,
                wordRepository,
                reviewLogRepository,
                goalRepository,
                achievementRepository,
                dictionaryCacheRepository,
                settingsRepository,
                aiCacheRepository,
                settingsService,
                deckService,
                validationService,
                importExportService,
                goalService,
                achievementService,
                reviewService,
                statsService,
                backupService,
                () -> dictionaryFactory.apply(dictionaryCacheRepository, settingsService),
                () -> aiFactory.apply(aiCacheRepository, settingsService),
                startupDeck
            );
        }
    }
}
