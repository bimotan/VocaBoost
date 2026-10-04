package com.vocabtrainer.app;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.repository.AchievementRepository;
import com.vocabtrainer.repository.AiCacheRepository;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DatabaseSnapshots;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.DictionaryCacheRepository;
import com.vocabtrainer.repository.EcdictRepository;
import com.vocabtrainer.repository.GoalRepository;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.SettingsRepository;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.AchievementService;
import com.vocabtrainer.service.AiService;
import com.vocabtrainer.service.AiServiceFactory;
import com.vocabtrainer.service.BackupService;
import com.vocabtrainer.service.CardStateBackfill;
import com.vocabtrainer.service.DeckService;
import com.vocabtrainer.service.DictionaryService;
import com.vocabtrainer.service.DictionaryServiceFactory;
import com.vocabtrainer.service.ExamPlanService;
import com.vocabtrainer.service.ExamSettings;
import com.vocabtrainer.service.GoalService;
import com.vocabtrainer.service.GoalSettings;
import com.vocabtrainer.service.ImportExportService;
import com.vocabtrainer.service.LocalDictionaryService;
import com.vocabtrainer.service.ReviewScheduler;
import com.vocabtrainer.service.ReviewService;
import com.vocabtrainer.service.ReviewSettings;
import com.vocabtrainer.service.SettingsService;
import com.vocabtrainer.service.SimilarityService;
import com.vocabtrainer.service.StarterImportService;
import com.vocabtrainer.service.StatsService;
import com.vocabtrainer.service.WordValidationService;
import com.vocabtrainer.service.cloze.ClozeMaker;
import com.vocabtrainer.service.ecdict.EcdictImportService;
import com.vocabtrainer.service.ecdict.EcdictTagDeckService;
import com.vocabtrainer.ui.Dialogs;
import com.vocabtrainer.ui.MainWindow;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The repositories and services of one database, wired the way the desktop app runs them.
 * {@link VocabTrainerApp} and the UI tests both build the app through {@link #builder(Path)}, so
 * the tests exercise the same wiring as the real app.
 *
 * @param ecdictRepository   the imported ECDICT dictionary, in its own file next to the database
 * @param localDictionary    the offline dictionaries (imported ECDICT, bundled starter words)
 * @param clozeMaker         finds a word and its inflected forms (also those ECDICT lists) in its example
 * @param dictionaryServices builds the dictionary service (offline dictionaries, then online ones,
 *                           which are skipped while offline mode is on)
 * @param aiServices         builds the AI service from the saved settings; called again when the
 *                           AI settings change
 * @param startupDeck        the deck the main window opens on
 * @param reviewScheduler    schedules reviews with the saved scheduling settings and exam dates; the
 *                           Settings tab changes them while the app runs, and the services read its
 *                           study day at every call
 * @param clock              the time every service and view works with
 * @param examPlanService    the exam dates, the countdown and the new-word plan
 * @param ecdictTagDecks     builds decks from the words ECDICT tags with an exam
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
    EcdictRepository ecdictRepository,
    EcdictImportService ecdictImportService,
    LocalDictionaryService localDictionary,
    ClozeMaker clozeMaker,
    Supplier<DictionaryService> dictionaryServices,
    Supplier<AiService> aiServices,
    Deck startupDeck,
    ReviewScheduler reviewScheduler,
    Clock clock,
    ExamPlanService examPlanService,
    EcdictTagDeckService ecdictTagDecks
) implements AutoCloseable {
    public static Builder builder(Path databasePath) {
        return new Builder(databasePath);
    }

    /** Closes the databases so SQLite checkpoints the write-ahead log and releases the files. */
    @Override
    public void close() {
        ecdictRepository.close();
        databaseManager.close();
    }

    /**
     * The main window on these services; {@code dialogs} shows its modal dialogs and file choosers, and
     * {@code systemLocale} is the computer's locale, which the language setting Auto follows.
     */
    public MainWindow createMainWindow(Dialogs dialogs, Locale systemLocale) {
        return new MainWindow(this, dialogs, systemLocale);
    }

    /** Calls {@code supplier} once, on first use, and returns that value from then on. */
    private static <T> Supplier<T> memoize(Supplier<T> supplier) {
        return new Supplier<>() {
            private T value;

            @Override
            public synchronized T get() {
                if (value == null) {
                    value = supplier.get();
                }
                return value;
            }
        };
    }

    /**
     * Opens one database. The app uses the defaults; tests replace the network-backed dictionary
     * and AI services, or a repository, before calling {@link #open()}.
     */
    public static final class Builder {
        private final Path databasePath;
        private Function<DatabaseManager, WordRepository> wordRepositoryFactory = WordRepository::new;
        private Function<DatabaseManager, ReviewLogRepository> reviewLogRepositoryFactory = ReviewLogRepository::new;
        /** Null for the app's chain, whose online dictionaries follow the saved offline mode. */
        private BiFunction<DictionaryCacheRepository, LocalDictionaryService, DictionaryService> dictionaryServiceFactory;
        private BiFunction<AiCacheRepository, SettingsService, AiService> aiServiceFactory = AiServiceFactory::create;
        private Clock clock = Clock.systemDefaultZone();

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

        /** Replaces the dictionary chain; {@code factory} gets the lookup cache and the offline dictionaries. */
        public Builder dictionaryService(BiFunction<DictionaryCacheRepository, LocalDictionaryService, DictionaryService> factory) {
            this.dictionaryServiceFactory = Objects.requireNonNull(factory);
            return this;
        }

        public Builder aiService(BiFunction<AiCacheRepository, SettingsService, AiService> factory) {
            this.aiServiceFactory = Objects.requireNonNull(factory);
            return this;
        }

        /**
         * The clock of the services and the views, which also dates new decks and words; the system
         * clock by default.
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock);
            return this;
        }

        /**
         * Creates or migrates the database, writes the day's snapshot of an existing one (see
         * {@link DatabaseSnapshots}), derives the FSRS state of words that have none yet,
         * resolves the startup deck, imports the starter words into a brand-new database and wires
         * the services.
         */
        public AppServices open() throws SQLException {
            DatabaseManager databaseManager = new DatabaseManager(databasePath);
            databaseManager.initialize();
            DatabaseSnapshots snapshots = new DatabaseSnapshots(databaseManager, clock);
            if (!databaseManager.isNewDatabase()) {
                // At most one a day; a failure is logged and never stops the start.
                snapshots.takeDailyIfDue();
            }

            DeckRepository deckRepository = new DeckRepository(databaseManager, clock);
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
            ExamSettings examSettings = new ExamSettings(settingsService);
            ReviewScheduler reviewScheduler = new ReviewScheduler(settingsService.getSchedulingOptions(), new Random(),
                examSettings::examDate);
            CardStateBackfill cardStates = new CardStateBackfill(wordRepository, reviewLogRepository, reviewScheduler);
            cardStates.run();
            ReviewSettings reviewSettings = new ReviewSettings(settingsService);
            GoalService goalService = new GoalService(goalRepository, reviewLogRepository,
                new GoalSettings(settingsService, reviewSettings), reviewScheduler::studyDay, clock);
            AchievementService achievementService = new AchievementService(achievementRepository, goalService, clock);
            WordValidationService validationService = new WordValidationService();
            // Only opened by the first lookup; the ECDICT CSV itself is never read here.
            EcdictRepository ecdictRepository = new EcdictRepository(databasePath.resolveSibling("ecdict.db"));
            EcdictImportService ecdictImportService = new EcdictImportService(ecdictRepository, clock);
            LocalDictionaryService localDictionary = new LocalDictionaryService(ecdictRepository);
            BiFunction<DictionaryCacheRepository, LocalDictionaryService, DictionaryService> dictionaryFactory =
                dictionaryServiceFactory != null
                    ? dictionaryServiceFactory
                    : (cache, local) -> DictionaryServiceFactory.create(cache, local, settingsService::isOfflineMode);
            // A word list import asks the network only when the user allows it, and builds the chain only then.
            ImportExportService importExportService = new ImportExportService(wordRepository, validationService,
                localDictionary, memoize(() -> dictionaryFactory.apply(dictionaryCacheRepository, localDictionary)),
                settingsService::isOfflineMode, clock);
            StarterImportService starterImportService = new StarterImportService(
                importExportService, wordRepository, reviewLogRepository, goalRepository, settingsService);
            starterImportService.importOnce(startupDeck.getId());
            ClozeMaker clozeMaker = new ClozeMaker(localDictionary::inflections);
            ReviewService reviewService = new ReviewService(
                wordRepository,
                reviewLogRepository,
                similarityService,
                reviewScheduler,
                goalService,
                achievementService,
                clock,
                reviewSettings,
                new Random(),
                clozeMaker
            );
            StatsService statsService = new StatsService(wordRepository, reviewLogRepository, clock,
                reviewScheduler::studyDay, reviewSettings);
            ExamPlanService examPlanService = new ExamPlanService(examSettings, wordRepository, reviewLogRepository,
                reviewSettings, reviewScheduler, clock);
            bringReviewsBeforeExams(examPlanService);
            BackupService backupService = new BackupService(deckRepository, wordRepository, reviewLogRepository,
                goalRepository, achievementRepository, databaseManager, validationService, clock, cardStates,
                snapshots);
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
                ecdictRepository,
                ecdictImportService,
                localDictionary,
                clozeMaker,
                () -> dictionaryFactory.apply(dictionaryCacheRepository, localDictionary),
                () -> aiFactory.apply(aiCacheRepository, settingsService),
                startupDeck,
                reviewScheduler,
                clock,
                examPlanService,
                new EcdictTagDeckService(ecdictRepository, deckService, wordRepository, validationService, clock)
            );
        }

        /**
         * Reviews that a backup restore, an import or the card-state backfill scheduled on or after an
         * exam are brought forward at every start; a failure only skips that.
         */
        private static void bringReviewsBeforeExams(ExamPlanService examPlanService) {
            try {
                examPlanService.bringReviewsBeforeExams();
            } catch (RuntimeException e) {
                Logger.getLogger(AppServices.class.getName()).log(Level.WARNING,
                    "Cannot bring the reviews scheduled after the exam forward", e);
            }
        }
    }
}
