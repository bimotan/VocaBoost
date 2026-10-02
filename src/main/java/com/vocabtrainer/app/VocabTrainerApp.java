package com.vocabtrainer.app;

import com.vocabtrainer.ui.ErrorDialogs;
import com.vocabtrainer.ui.JavaFxDialogs;
import com.vocabtrainer.ui.MainWindow;
import com.vocabtrainer.util.AppLogging;
import com.vocabtrainer.util.DateTimeUtil;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.logging.Level;
import java.util.logging.Logger;

public class VocabTrainerApp extends Application {
    private static final Logger LOGGER = Logger.getLogger(VocabTrainerApp.class.getName());

    private AppServices services;

    @Override
    public void start(Stage stage) {
        AppLogging.initialize();
        ErrorDialogs.installUncaughtExceptionHandler();
        try {
            services = AppServices.builder(DateTimeUtil.defaultDatabasePath()).open();
            showMainWindow(stage, services.createMainWindow(new JavaFxDialogs()));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Startup failed", e);
            ErrorDialogs.exceptionAlert("Startup failed", "The app could not start", e).showAndWait();
        }
    }

    /** Puts the main window's scene on {@code stage} and shows it; the UI tests open the window the same way. */
    public static void showMainWindow(Stage stage, MainWindow mainWindow) {
        Scene scene = mainWindow.createScene();
        stage.setTitle("VocaBoost");
        stage.setMinWidth(1100);
        stage.setMinHeight(760);
        stage.setScene(scene);
        stage.show();
    }

    /** Closes the database so SQLite checkpoints the write-ahead log and releases the file. */
    @Override
    public void stop() {
        if (services != null) {
            services.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
