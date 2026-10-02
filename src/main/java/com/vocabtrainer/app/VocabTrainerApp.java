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
import com.vocabtrainer.ui.ErrorDialogs;
import com.vocabtrainer.ui.MainWindow;
import com.vocabtrainer.util.AppLogging;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.logging.Level;
import java.util.logging.Logger;

public class VocabTrainerApp extends Application {
    private static final Logger LOGGER = Logger.getLogger(VocabTrainerApp.class.getName());

    @Override
    public void start(Stage stage) {
        AppLogging.initialize();
        ErrorDialogs.installUncaughtExceptionHandler();
        try {
            DatabaseManager databaseManager = new DatabaseManager();
            databaseManager.initialize();

            DeckRepository deckRepository = new DeckRepository(databaseManager);
            WordRepository wordRepository = new WordRepository(databaseManager);
            ReviewLogRepository reviewLogRepository = new ReviewLogRepository(databaseManager);
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
            DictionaryService dictionaryService = DictionaryServiceFactory.create(dictionaryCacheRepository, settingsService);
            BackupService backupService = new BackupService(deckRepository, wordRepository, reviewLogRepository,
                goalRepository, achievementRepository, databaseManager, validationService);
            AiService aiService = AiServiceFactory.create(aiCacheRepository, settingsService);

            MainWindow mainWindow = new MainWindow(
                startupDeck,
                deckService,
                wordRepository,
                reviewService,
                importExportService,
                statsService,
                goalService,
                achievementService,
                dictionaryService,
                dictionaryCacheRepository,
                aiCacheRepository,
                settingsService,
                validationService,
                backupService,
                aiService,
                databaseManager.getDatabasePath()
            );
            Scene scene = mainWindow.createScene();
            stage.setTitle("VocaBoost");
            stage.setMinWidth(1100);
            stage.setMinHeight(760);
            stage.setScene(scene);
            stage.show();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Startup failed", e);
            ErrorDialogs.exceptionAlert("Startup failed", "The app could not start", e).showAndWait();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
