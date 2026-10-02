package com.vocabtrainer.ui.settings;

import com.vocabtrainer.repository.AiCacheRepository;
import com.vocabtrainer.service.DisplaySettings;
import com.vocabtrainer.service.GoalSettings;
import com.vocabtrainer.service.LocalDictionaryService;
import com.vocabtrainer.service.ReviewSettings;
import com.vocabtrainer.service.SchedulingSettings;
import com.vocabtrainer.service.SettingsService;
import com.vocabtrainer.service.ecdict.EcdictImportService;
import com.vocabtrainer.ui.ConfiguredServices;
import com.vocabtrainer.ui.Folders;
import com.vocabtrainer.ui.OfflineMode;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import com.vocabtrainer.util.AppLogging;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntConsumer;

/**
 * The Settings tab: study settings (scheduler, new words per day, goals), offline mode (the same
 * switch as the header's), the ECDICT dictionary, the AI provider, the data and log folders, the
 * text size and the language. Every setting is saved in the {@code settings} table when it is
 * changed and applies at once.
 */
public final class SettingsView {
    private final ViewContext context;
    private final Path databasePath;
    private final DisplaySettings display;
    private final IntConsumer applyTextSize;
    private final ComboBox<Integer> textSizeSelector = new ComboBox<>();
    private final Tab tab;
    /** Set while the text size selector is made to show the saved size, which is not saved again. */
    private boolean showingSavedTextSize;

    /**
     * {@code databasePath} is the database file, whose folder "Open data folder" opens;
     * {@code applyTextSize} shows the window's text at a saved text size.
     */
    public SettingsView(ViewContext context, SettingsService settingsService, SchedulingSettings scheduling,
                        ReviewSettings reviewSettings, GoalSettings goalSettings, AiCacheRepository aiCacheRepository,
                        EcdictImportService ecdictImportService, LocalDictionaryService localDictionary,
                        ConfiguredServices configured, OfflineMode offlineMode, Path databasePath,
                        DisplaySettings display, IntConsumer applyTextSize) {
        this.context = context;
        this.databasePath = databasePath;
        this.display = display;
        this.applyTextSize = applyTextSize;

        StudySettingsBox study = new StudySettingsBox(context, scheduling, reviewSettings, goalSettings);
        EcdictSettingsBox ecdict = new EcdictSettingsBox(context, settingsService, ecdictImportService, localDictionary);
        AiSettingsBox ai = new AiSettingsBox(context, settingsService, aiCacheRepository, configured);

        List<Node> sections = new ArrayList<>();
        for (Node section : List.of(study.root(), offlineSection(offlineMode), ecdict.root(), ai.root(),
            dataSection(), displaySection(), languageSection())) {
            if (!sections.isEmpty()) {
                sections.add(new Separator());
            }
            sections.add(section);
        }
        VBox content = new VBox(18);
        content.getChildren().setAll(sections);
        content.setId("settingsContent");
        content.setPadding(new Insets(24));
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        tab = Widgets.tab("settingsTab", "Settings", scrollPane);
    }

    public Tab tab() {
        return tab;
    }

    private static Node offlineSection(OfflineMode offlineMode) {
        return new VBox(10, Widgets.sectionTitle("Online Services"),
            offlineMode.checkBox("settingsOfflineModeToggle", "Offline mode / 离线模式"),
            note(OfflineMode.DESCRIPTION + " The switch in the window header is the same setting."));
    }

    /** The folders are opened, not shown: their paths name the user's account. */
    private Node dataSection() {
        Button dataFolderButton = new Button("Open data folder");
        dataFolderButton.setId("openDataFolderButton");
        dataFolderButton.setOnAction(event ->
            Folders.open(context.errors(), "Data folder", databasePath.toAbsolutePath().getParent()));
        Button logFolderButton = new Button("Open log folder");
        logFolderButton.setId("openLogFolderButton");
        logFolderButton.setOnAction(event -> openLogFolder());
        return new VBox(10, Widgets.sectionTitle("Data and Logs"), new HBox(10, dataFolderButton, logFolderButton),
            note("The data folder holds vocab.db (words, reviews, goals and settings, including a saved API key)"
                + " and the imported ECDICT dictionary, ecdict.db. The log folder holds the app's logs, which help"
                + " when something went wrong."));
    }

    private void openLogFolder() {
        Optional<Path> logDirectory = AppLogging.logDirectory();
        if (logDirectory.isEmpty()) {
            context.errors().showInfo("File logging is unavailable; logs are written to the console only.");
            return;
        }
        Folders.open(context.errors(), "Log folder", logDirectory.get());
    }

    /**
     * The text size of the window and its dialogs: 100% (the system's), 115% or 130%. A size that
     * cannot be saved is reported and the selector goes back to the size in effect.
     */
    private Node displaySection() {
        textSizeSelector.setId("textSizeSelector");
        textSizeSelector.getItems().setAll(DisplaySettings.TEXT_SIZES);
        textSizeSelector.setCellFactory(list -> textSizeCell());
        textSizeSelector.setButtonCell(textSizeCell());
        showSavedTextSize();
        textSizeSelector.valueProperty().addListener((observable, oldSize, size) -> {
            if (showingSavedTextSize || size == null) {
                return;
            }
            try {
                display.saveTextSizePercent(size);
            } catch (RuntimeException e) {
                context.errors().reportFailure("Text size not saved", e);
                showSavedTextSize();
                return;
            }
            applyTextSize.accept(size);
        });
        HBox row = new HBox(12, Widgets.formLabel("Te_xt size", textSizeSelector), textSizeSelector);
        row.setAlignment(Pos.CENTER_LEFT);
        return new VBox(10, Widgets.sectionTitle("Display"), row,
            note("Makes the text of the window and its dialogs larger; 100% is the system's text size."));
    }

    private void showSavedTextSize() {
        showingSavedTextSize = true;
        try {
            textSizeSelector.setValue(display.textSizePercent());
        } finally {
            showingSavedTextSize = false;
        }
    }

    private static ListCell<Integer> textSizeCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Integer percent, boolean empty) {
                super.updateItem(percent, empty);
                setText(empty || percent == null ? null : percent + "%"
                    + (percent == DisplaySettings.DEFAULT_TEXT_SIZE ? " (system size)" : ""));
            }
        };
    }

    /** A placeholder until the app is translated: English is the only language. */
    private static Node languageSection() {
        ComboBox<String> languageSelector = new ComboBox<>();
        languageSelector.setId("languageSelector");
        languageSelector.getItems().add("English");
        languageSelector.getSelectionModel().selectFirst();
        languageSelector.setDisable(true);
        HBox row = new HBox(12, Widgets.formLabel("Interface language", languageSelector), languageSelector);
        row.setAlignment(Pos.CENTER_LEFT);
        return new VBox(10, Widgets.sectionTitle("Language"), row,
            note("The app is in English for now; other languages will be offered here."));
    }

    private static Label note(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        label.getStyleClass().add("muted-text");
        return label;
    }
}
