package com.vocabtrainer.app;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.ImportExportService;
import com.vocabtrainer.service.MockAiService;
import com.vocabtrainer.service.ReviewScheduler;
import com.vocabtrainer.service.ReviewService;
import com.vocabtrainer.service.SimilarityService;
import com.vocabtrainer.service.StatsService;
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
            Deck defaultDeck = deckRepository.ensureDefaultDeck();

            SimilarityService similarityService = new SimilarityService();
            ReviewScheduler reviewScheduler = new ReviewScheduler();
            ReviewService reviewService = new ReviewService(
                wordRepository,
                reviewLogRepository,
                similarityService,
                reviewScheduler
            );
            ImportExportService importExportService = new ImportExportService(wordRepository);
            StatsService statsService = new StatsService(wordRepository, reviewLogRepository);

            MainWindow mainWindow = new MainWindow(
                defaultDeck,
                wordRepository,
                reviewService,
                importExportService,
                statsService,
                new MockAiService(),
                databaseManager.getDatabasePath()
            );
            Scene scene = mainWindow.createScene();
            stage.setTitle("Vocabulary Trainer");
            stage.setMinWidth(980);
            stage.setMinHeight(680);
            stage.setScene(scene);
            stage.show();
        } catch (Exception e) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("启动失败");
            alert.setHeaderText("应用无法启动");
            alert.setContentText(e.getMessage());
            alert.showAndWait();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}