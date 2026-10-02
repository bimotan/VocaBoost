package com.vocabtrainer.ui;

import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.ui.dashboard.DashboardView;
import com.vocabtrainer.ui.decks.DecksView;
import com.vocabtrainer.ui.importing.AddImportView;
import com.vocabtrainer.ui.review.ReviewView;
import com.vocabtrainer.ui.stats.StatisticsView;
import com.vocabtrainer.ui.words.WordListView;
import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;

import java.nio.file.Path;

/**
 * The main window: the deck header above one tab per area. Each tab is its own view class; this
 * shell only builds them on the shared {@link ViewContext} (current deck, change notifications,
 * background work, dialogs and error reporting).
 */
public class MainWindow {
    private final AppServices services;
    private final Dialogs dialogs;

    /** The main window on {@code services}; {@code dialogs} shows every modal dialog and file chooser. */
    public MainWindow(AppServices services, Dialogs dialogs) {
        this.services = services;
        this.dialogs = dialogs;
    }

    public Scene createScene() {
        UiErrors errors = new UiErrors(dialogs);
        DeckContext decks = new DeckContext(services.deckService(), services.settingsService(), services.startupDeck());
        ConfiguredServices configured = new ConfiguredServices(services.dictionaryServices(), services.aiServices());
        BorderPane root = new BorderPane();
        ViewContext context = new ViewContext(dialogs, errors, new UiAsync(errors), new DataChanges(), decks,
            () -> root.getScene().getWindow());
        Path databasePath = services.databaseManager().getDatabasePath();

        DeckHeader header = new DeckHeader(context, services.deckService(), services.settingsService(), configured);
        DashboardView dashboard = new DashboardView(context, services.statsService(), services.goalService(),
            services.achievementService(), databasePath);
        DecksView decksView = new DecksView(context, services.deckService(), services.statsService());
        ReviewView review = new ReviewView(context, services.reviewService(), services.goalService(), configured,
            services.clock());
        AddImportView addImport = new AddImportView(context, services.wordRepository(), services.validationService(),
            services.importExportService(),
            services.settingsService(), services.aiCacheRepository(), services.ecdictImportService(),
            services.localDictionary(), configured);
        StatisticsView statistics = new StatisticsView(context, services.statsService(), services.goalService(),
            services.backupService(), databasePath);
        WordListView wordList = new WordListView(context, services.wordRepository(), services.reviewLogRepository(),
            services.validationService(), services.clock(), services.reviewScheduler().studyDay(),
            services.clozeMaker());

        TabPane tabs = new TabPane();
        tabs.setId("mainTabs");
        tabs.getTabs().addAll(dashboard.tab(), decksView.tab(), review.tab(), addImport.tab(), statistics.tab(),
            wordList.tab());
        root.setTop(header.root());
        root.setCenter(tabs);

        // The selected tab (Dashboard) has loaded itself. The tables are filled before their first
        // layout too, which sizes their columns to the rows; after that, hidden tabs wait until shown.
        decks.reloadDecks();
        decksView.refreshNow();
        wordList.refreshNow();
        review.start();
        Scene scene = new Scene(root, 1120, 780);
        review.installShortcuts(scene);
        return scene;
    }
}
