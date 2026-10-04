package com.vocabtrainer.ui.settings;

import com.vocabtrainer.repository.AiCacheRepository;
import com.vocabtrainer.service.DisplaySettings;
import com.vocabtrainer.service.ExamPlanService;
import com.vocabtrainer.service.GoalSettings;
import com.vocabtrainer.service.LanguageSettings;
import com.vocabtrainer.service.LanguageSettings.Language;
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
import com.vocabtrainer.util.Messages;
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
import java.util.Locale;
import java.util.Optional;
import java.util.function.IntConsumer;

import static com.vocabtrainer.util.Messages.tr;

/**
 * The Settings tab: study settings (scheduler, new words per day, goals, exam date), offline mode (the same
 * switch as the header's), the ECDICT dictionary, the AI provider, the data and log folders, the
 * text size and the language. Every setting is saved in the {@code settings} table when it is
 * changed and applies at once, except the language, which applies when the app starts again.
 */
public final class SettingsView {
    private final ViewContext context;
    private final Path databasePath;
    private final DisplaySettings display;
    private final IntConsumer applyTextSize;
    private final LanguageSettings languages;
    private final Locale systemLocale;
    private final ComboBox<Integer> textSizeSelector = new ComboBox<>();
    private final ComboBox<Language> languageSelector = new ComboBox<>();
    private final Label languageNote = new Label();
    private final Tab tab;
    /** Set while the text size selector is made to show the saved size, which is not saved again. */
    private boolean showingSavedTextSize;
    /** Set while the language selector is made to show the saved language, which is not saved again. */
    private boolean showingSavedLanguage;

    /**
     * {@code databasePath} is the database file, whose folder "Open data folder" opens;
     * {@code applyTextSize} shows the window's text at a saved text size; {@code systemLocale} is the
     * computer's locale, which the language Auto follows.
     */
    public SettingsView(ViewContext context, SettingsService settingsService, SchedulingSettings scheduling,
                        ReviewSettings reviewSettings, GoalSettings goalSettings, ExamPlanService examPlans,
                        AiCacheRepository aiCacheRepository,
                        EcdictImportService ecdictImportService, LocalDictionaryService localDictionary,
                        ConfiguredServices configured, OfflineMode offlineMode, Path databasePath,
                        DisplaySettings display, IntConsumer applyTextSize, LanguageSettings languages,
                        Locale systemLocale) {
        this.context = context;
        this.databasePath = databasePath;
        this.display = display;
        this.applyTextSize = applyTextSize;
        this.languages = languages;
        this.systemLocale = systemLocale;

        StudySettingsBox study = new StudySettingsBox(context, scheduling, reviewSettings, goalSettings, examPlans);
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
        tab = Widgets.tab("settingsTab", tr("settings.tab"), scrollPane);
        study.refreshWhenShown(tab);
    }

    public Tab tab() {
        return tab;
    }

    private static Node offlineSection(OfflineMode offlineMode) {
        return new VBox(10, Widgets.sectionTitle(tr("settings.online.title")),
            offlineMode.checkBox("settingsOfflineModeToggle", tr("offline.toggle")),
            note(tr("settings.online.note", OfflineMode.description())));
    }

    /** The folders are opened, not shown: their paths name the user's account. */
    private Node dataSection() {
        Button dataFolderButton = new Button(tr("settings.data.openDataFolder"));
        dataFolderButton.setId("openDataFolderButton");
        dataFolderButton.setOnAction(event ->
            Folders.open(context.errors(), tr("folder.data"), databasePath.toAbsolutePath().getParent()));
        Button logFolderButton = new Button(tr("settings.data.openLogFolder"));
        logFolderButton.setId("openLogFolderButton");
        logFolderButton.setOnAction(event -> openLogFolder());
        return new VBox(10, Widgets.sectionTitle(tr("settings.data.title")), new HBox(10, dataFolderButton, logFolderButton),
            note(tr("settings.data.note")));
    }

    private void openLogFolder() {
        Optional<Path> logDirectory = AppLogging.logDirectory();
        if (logDirectory.isEmpty()) {
            context.errors().showInfo(tr("settings.data.noLogFolder"));
            return;
        }
        Folders.open(context.errors(), tr("folder.log"), logDirectory.get());
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
                context.errors().reportFailure(tr("settings.display.textSize.failed"), e);
                showSavedTextSize();
                return;
            }
            applyTextSize.accept(size);
        });
        HBox row = new HBox(12, Widgets.formLabel(tr("settings.display.textSize"), textSizeSelector), textSizeSelector);
        row.setAlignment(Pos.CENTER_LEFT);
        return new VBox(10, Widgets.sectionTitle(tr("settings.display.title")), row,
            note(tr("settings.display.note")));
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
                setText(empty || percent == null ? null
                    : percent == DisplaySettings.DEFAULT_TEXT_SIZE ? tr("settings.display.textSize.system", percent)
                    : tr("settings.display.textSize.percent", percent));
            }
        };
    }

    /**
     * The language of the app: Auto (the computer's language), Simplified Chinese or English. It is
     * saved at once and applies when the app starts again, which the note under it says in the chosen
     * language; a language that cannot be saved is reported and the selector goes back to the saved one.
     */
    private Node languageSection() {
        languageSelector.setId("languageSelector");
        languageSelector.getItems().setAll(Language.values());
        languageSelector.setCellFactory(list -> languageCell());
        languageSelector.setButtonCell(languageCell());
        showSavedLanguage();
        languageSelector.valueProperty().addListener((observable, oldLanguage, language) -> {
            if (showingSavedLanguage || language == null) {
                return;
            }
            try {
                languages.saveLanguage(language);
            } catch (RuntimeException e) {
                context.errors().reportFailure(tr("settings.language.failed"), e);
                showSavedLanguage();
                return;
            }
            showLanguageNote(language);
        });
        languageNote.setId("languageNoteLabel");
        languageNote.setWrapText(true);
        languageNote.setMinHeight(Region.USE_PREF_SIZE);
        languageNote.getStyleClass().add("muted-text");
        showLanguageNote(languageSelector.getValue());
        HBox row = new HBox(12, Widgets.formLabel(tr("settings.language.label"), languageSelector), languageSelector);
        row.setAlignment(Pos.CENTER_LEFT);
        return new VBox(10, Widgets.sectionTitle(tr("settings.language.title")), row, languageNote);
    }

    private void showSavedLanguage() {
        showingSavedLanguage = true;
        try {
            languageSelector.setValue(languages.language());
        } finally {
            showingSavedLanguage = false;
        }
    }

    /**
     * What the language selector applies: nothing more when the app is shown in that language already,
     * otherwise that it applies once the app starts again, said in the chosen language.
     */
    private void showLanguageNote(Language language) {
        Locale chosen = language.locale(systemLocale);
        languageNote.setText(chosen.equals(Messages.locale()) ? tr("settings.language.note")
            : Messages.trIn(chosen, "settings.language.restart"));
    }

    private ListCell<Language> languageCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Language language, boolean empty) {
                super.updateItem(language, empty);
                setText(empty || language == null ? null : switch (language) {
                    case AUTO -> tr("settings.language.auto",
                        language.locale(systemLocale).equals(Messages.SIMPLIFIED_CHINESE)
                            ? tr("settings.language.chinese") : tr("settings.language.english"));
                    case SIMPLIFIED_CHINESE -> tr("settings.language.chinese");
                    case ENGLISH -> tr("settings.language.english");
                });
            }
        };
    }

    private static Label note(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        label.getStyleClass().add("muted-text");
        return label;
    }
}
