package com.vocabtrainer.app;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.repository.AchievementRepository;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.DictionaryCacheRepository;
import com.vocabtrainer.repository.GoalRepository;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.AchievementService;
import com.vocabtrainer.service.DictionaryService;
import com.vocabtrainer.service.DictionaryServiceFactory;
import com.vocabtrainer.service.GoalService;
import com.vocabtrainer.service.ImportExportService;
import com.vocabtrainer.service.MockAiService;
import com.vocabtrainer.service.ReviewScheduler;
import com.vocabtrainer.service.ReviewService;
import com.vocabtrainer.service.SimilarityService;
import com.vocabtrainer.service.StatsService;
import com.vocabtrainer.service.WordValidationService;
import com.vocabtrainer.ui.MainWindow;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

public class VocabTrainerApp extends Application {
    @Override
    public void start(Stage stage) {
        try {
            DatabaseManager databaseManager = new DatabaseManager();
            databaseManager.initialize();

            DeckRepository deckRepository = new DeckRepository(databaseManager);
            WordRepository wordRepository = new WordRepository(databaseManager);
            ReviewLogRepository reviewLogRepository = new ReviewLogRepository(databaseManager);
            GoalRepository goalRepository = new GoalRepository(databaseManager);
            AchievementRepository achievementRepository = new AchievementRepository(databaseManager);
            DictionaryCacheRepository dictionaryCacheRepository = new DictionaryCacheRepository(databaseManager);
            Deck defaultDeck = deckRepository.ensureDefaultDeck();

            SimilarityService similarityService = new SimilarityService();
            ReviewScheduler reviewScheduler = new ReviewScheduler();
            GoalService goalService = new GoalService(goalRepository);
            AchievementService achievementService = new AchievementService(achievementRepository, goalService);
            WordValidationService validationService = new WordValidationService();
            ImportExportService importExportService = new ImportExportService(wordRepository, validationService);
            if (wordRepository.countAll(defaultDeck.getId()) == 0) {
                importExportService.importBundledGreStarter(defaultDeck.getId());
            }
            ReviewService reviewService = new ReviewService(
                wordRepository,
                reviewLogRepository,
                similarityService,
                reviewScheduler,
                goalService,
                achievementService
            );
            StatsService statsService = new StatsService(wordRepository, reviewLogRepository, databaseManager);
            DictionaryService dictionaryService = DictionaryServiceFactory.create(dictionaryCacheRepository);

            MainWindow mainWindow = new MainWindow(
                defaultDeck,
                wordRepository,
                reviewService,
                importExportService,
                statsService,
                goalService,
                achievementService,
                dictionaryService,
                validationService,
                new MockAiService(),
                databaseManager.getDatabasePath()
            );
            Scene scene = mainWindow.createScene();
            stage.setTitle("VocaBoost");
            stage.setMinWidth(1100);
            stage.setMinHeight(760);
            stage.setScene(scene);
            stage.show();
        } catch (Exception e) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Startup failed");
            alert.setHeaderText("The app could not start");
            alert.setContentText(e.getMessage());
            alert.showAndWait();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
