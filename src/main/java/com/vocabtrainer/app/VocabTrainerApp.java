package com.vocabtrainer.app;

import com.vocabtrainer.service.DisplaySettings;
import com.vocabtrainer.service.LanguageSettings;
import com.vocabtrainer.ui.ErrorDialogs;
import com.vocabtrainer.ui.JavaFxDialogs;
import com.vocabtrainer.ui.MainWindow;
import com.vocabtrainer.ui.WindowSize;
import com.vocabtrainer.util.AppLogging;
import com.vocabtrainer.util.DataFolder;
import com.vocabtrainer.util.DateTimeUtil;
import com.vocabtrainer.util.Messages;
import javafx.application.Application;
import javafx.geometry.Dimension2D;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.net.URL;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

public class VocabTrainerApp extends Application {
    private static final Logger LOGGER = Logger.getLogger(VocabTrainerApp.class.getName());
    /** The computer's locale, read before the app sets the default locale to its language. */
    private static final Locale SYSTEM_LOCALE = Locale.getDefault();
    /** The sizes of the app icon in src/main/resources/icons (rendered by packaging/icons/make-icons.sh). */
    static final List<Integer> ICON_SIZES = List.of(16, 32, 48, 64, 128, 256, 512);

    private AppServices services;

    @Override
    public void start(Stage stage) {
        AppLogging.initialize();
        ErrorDialogs.installUncaughtExceptionHandler();
        try {
            openMainWindow(stage, DateTimeUtil.defaultDatabasePath());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Startup failed", e);
            ErrorDialogs.exceptionAlert(Messages.tr("startup.failed.title"), Messages.tr("startup.failed.header"), e)
                .showAndWait();
        }
    }

    /**
     * Opens the database at {@code databasePath} and shows the main window on {@code stage}, in the
     * saved language; {@link #stop()} closes the database. {@link SmokeTest} starts the app the same
     * way on a temporary folder.
     */
    void openMainWindow(Stage stage, Path databasePath) throws SQLException {
        // The database can hold the AI API key: keep the folder private where the system allows it.
        DataFolder.prepare(databasePath.toAbsolutePath().getParent());
        // Until the saved language is read, e.g. for a database that cannot be opened, follow the computer's.
        useLanguage(LanguageSettings.Language.AUTO, SYSTEM_LOCALE);
        services = AppServices.builder(databasePath).open();
        useLanguage(new LanguageSettings(services.settingsService()).language(), SYSTEM_LOCALE);
        // The dialogs show their text at the size the Settings tab saved, read when each one opens.
        DisplaySettings display = new DisplaySettings(services.settingsService());
        showMainWindow(stage, services.createMainWindow(new JavaFxDialogs(display::textSizePercent), SYSTEM_LOCALE));
    }

    /**
     * Shows the app in {@code language}, Auto following {@code systemLocale}, from now on: the texts,
     * and JavaFX's own (the buttons of dialogs, the menus of text fields, the date picker), which follow
     * the default locale. The main window is built after this; the UI tests open it the same way.
     *
     * @return the locale the app is shown in
     */
    public static Locale useLanguage(LanguageSettings.Language language, Locale systemLocale) {
        Locale locale = language.locale(systemLocale);
        Messages.setLocale(locale);
        Locale.setDefault(locale);
        return locale;
    }

    /**
     * Puts the main window's scene on {@code stage} and shows it; the UI tests open the window the same
     * way. The window can be made as small as {@link WindowSize} allows, which fits a small laptop.
     */
    public static void showMainWindow(Stage stage, MainWindow mainWindow) {
        Scene scene = mainWindow.createScene();
        stage.setTitle(Messages.tr("app.title"));
        stage.getIcons().setAll(icons());
        Dimension2D minimum = WindowSize.minimumWindowSize(Screen.getPrimary().getVisualBounds());
        stage.setMinWidth(minimum.getWidth());
        stage.setMinHeight(minimum.getHeight());
        stage.setScene(scene);
        stage.show();
    }

    /**
     * The app icon in every size it comes in, for the window's title bar and the task bar, which pick
     * the size they need. A size that cannot be read is logged and left out.
     */
    static List<Image> icons() {
        List<Image> icons = new ArrayList<>();
        for (int size : ICON_SIZES) {
            String name = "/icons/vocaboost-" + size + ".png";
            URL url = VocabTrainerApp.class.getResource(name);
            Image icon = url == null ? null : new Image(url.toExternalForm());
            if (icon == null || icon.isError()) {
                LOGGER.warning("Cannot read the app icon " + name);
            } else {
                icons.add(icon);
            }
        }
        return icons;
    }

    /** Closes the database so SQLite checkpoints the write-ahead log and releases the file. */
    @Override
    public void stop() {
        if (services != null) {
            services.close();
        }
    }

    public static void main(String[] args) {
        if (SmokeTest.requested(args)) {
            System.exit(SmokeTest.run(System.out, System.err));
        }
        launch(args);
    }
}
