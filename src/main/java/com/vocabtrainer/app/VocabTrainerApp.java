package com.vocabtrainer.app;

import com.vocabtrainer.service.DisplaySettings;
import com.vocabtrainer.ui.ErrorDialogs;
import com.vocabtrainer.ui.JavaFxDialogs;
import com.vocabtrainer.ui.MainWindow;
import com.vocabtrainer.ui.WindowSize;
import com.vocabtrainer.util.AppLogging;
import com.vocabtrainer.util.DataFolder;
import com.vocabtrainer.util.DateTimeUtil;
import javafx.application.Application;
import javafx.geometry.Dimension2D;
import javafx.scene.Scene;
import javafx.stage.Screen;
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
        // The database can hold the AI API key: keep the folder private where the system allows it.
        DataFolder.prepare(DateTimeUtil.defaultDatabasePath().toAbsolutePath().getParent());
        try {
            services = AppServices.builder(DateTimeUtil.defaultDatabasePath()).open();
            // The dialogs show their text at the size the Settings tab saved, read when each one opens.
            DisplaySettings display = new DisplaySettings(services.settingsService());
            showMainWindow(stage, services.createMainWindow(new JavaFxDialogs(display::textSizePercent)));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Startup failed", e);
            ErrorDialogs.exceptionAlert("Startup failed", "The app could not start", e).showAndWait();
        }
    }

    /**
     * Puts the main window's scene on {@code stage} and shows it; the UI tests open the window the same
     * way. The window can be made as small as {@link WindowSize} allows, which fits a small laptop.
     */
    public static void showMainWindow(Stage stage, MainWindow mainWindow) {
        Scene scene = mainWindow.createScene();
        stage.setTitle("VocaBoost");
        Dimension2D minimum = WindowSize.minimumWindowSize(Screen.getPrimary().getVisualBounds());
        stage.setMinWidth(minimum.getWidth());
        stage.setMinHeight(minimum.getHeight());
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
