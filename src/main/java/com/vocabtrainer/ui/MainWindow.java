package com.vocabtrainer.ui;

import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.service.DisplaySettings;
import com.vocabtrainer.service.LanguageSettings;
import com.vocabtrainer.service.ReviewSettings;
import com.vocabtrainer.service.SchedulingSettings;
import com.vocabtrainer.ui.dashboard.DashboardView;
import com.vocabtrainer.ui.decks.DecksView;
import com.vocabtrainer.ui.importing.AddImportView;
import com.vocabtrainer.ui.review.ReviewView;
import com.vocabtrainer.ui.settings.SettingsView;
import com.vocabtrainer.ui.stats.StatisticsView;
import com.vocabtrainer.ui.words.WordListView;
import javafx.geometry.Dimension2D;
import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.stage.Screen;

import java.nio.file.Path;
import java.util.Locale;

/**
 * The main window: the deck header above one tab per area. Each tab is its own view class; this
 * shell only builds them on the shared {@link ViewContext} (current deck, change notifications,
 * background work, dialogs and error reporting). It sizes the window to the screen
 * ({@link WindowSize}), styles it with app.css and shows its text at the saved text size.
 */
public class MainWindow {
    private final AppServices services;
    private final Dialogs dialogs;
    private final Locale systemLocale;

    /**
     * The main window on {@code services}; {@code dialogs} shows every modal dialog and file chooser.
     * Its texts are in the language the app was started in; {@code systemLocale} is the computer's
     * locale, which the language setting Auto follows.
     */
    public MainWindow(AppServices services, Dialogs dialogs, Locale systemLocale) {
        this.services = services;
        this.dialogs = dialogs;
        this.systemLocale = systemLocale;
    }

    public Scene createScene() {
        UiErrors errors = new UiErrors(dialogs);
        DeckContext decks = new DeckContext(services.deckService(), services.settingsService(), services.startupDeck());
        ConfiguredServices configured = new ConfiguredServices(services.dictionaryServices(), services.aiServices());
        BorderPane root = new BorderPane();
        ViewContext context = new ViewContext(dialogs, errors, new UiAsync(errors), new DataChanges(), decks,
            () -> root.getScene().getWindow());
        Path databasePath = services.databaseManager().getDatabasePath();
        OfflineMode offlineMode = new OfflineMode(context, services.settingsService());
        DisplaySettings display = new DisplaySettings(services.settingsService());
        AppStyle.applyTextSize(root, display.textSizePercent());

        DeckHeader header = new DeckHeader(context, services.deckService(), services.settingsService(), configured,
            offlineMode);
        DashboardView dashboard = new DashboardView(context, services.statsService(), services.goalService(),
            services.achievementService(), services.examPlanService(), databasePath);
        DecksView decksView = new DecksView(context, services.deckService(), services.statsService(),
            services.ecdictTagDecks());
        ReviewView review = new ReviewView(context, services.reviewService(), services.goalService(), configured,
            services.settingsService(), services.clock());
        AddImportView addImport = new AddImportView(context, services.wordRepository(), services.validationService(),
            services.importExportService(), services.settingsService(), configured);
        StatisticsView statistics = new StatisticsView(context, services.statsService(), services.goalService(),
            services.backupService(), services.examPlanService(),
            () -> services.reviewScheduler().options().desiredRetention());
        WordListView wordList = new WordListView(context, services.wordRepository(), services.reviewLogRepository(),
            services.validationService(), services.clock(), services.reviewScheduler()::studyDay,
            services.clozeMaker());
        SettingsView settings = new SettingsView(context, services.settingsService(),
            new SchedulingSettings(services.settingsService(), services.reviewScheduler()),
            new ReviewSettings(services.settingsService()), services.goalService().settings(),
            services.examPlanService(), services.aiCacheRepository(), services.ecdictImportService(), services.localDictionary(), configured,
            offlineMode, databasePath, display, percent -> AppStyle.applyTextSize(root, percent),
            new LanguageSettings(services.settingsService()), systemLocale);

        TabPane tabs = new TabPane();
        tabs.setId("mainTabs");
        tabs.getTabs().addAll(dashboard.tab(), decksView.tab(), review.tab(), addImport.tab(), statistics.tab(),
            wordList.tab(), settings.tab());
        root.setTop(header.root());
        root.setCenter(tabs);

        // The selected tab (Dashboard) has loaded itself. The tables are filled before their first
        // layout too, which sizes their columns to the rows; after that, hidden tabs wait until shown.
        decks.reloadDecks();
        decksView.refreshNow();
        wordList.refreshNow();
        review.start();
        Dimension2D size = WindowSize.initialSceneSize(Screen.getPrimary().getVisualBounds());
        Scene scene = new Scene(root, size.getWidth(), size.getHeight());
        AppStyle.install(scene);
        review.installShortcuts(scene);
        return scene;
    }
}
