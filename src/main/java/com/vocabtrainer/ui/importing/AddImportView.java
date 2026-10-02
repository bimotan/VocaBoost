package com.vocabtrainer.ui.importing;

import com.vocabtrainer.repository.AiCacheRepository;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.AchievementService;
import com.vocabtrainer.service.GoalService;
import com.vocabtrainer.service.ImportExportService;
import com.vocabtrainer.service.LocalDictionaryService;
import com.vocabtrainer.service.SettingsService;
import com.vocabtrainer.service.WordValidationService;
import com.vocabtrainer.service.ecdict.EcdictImportService;
import com.vocabtrainer.ui.ConfiguredServices;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import javafx.geometry.Insets;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.layout.VBox;

/** The Add / Import tab: manual add with dictionary lookup, dictionary and AI settings, and file imports. */
public final class AddImportView {
    private final Tab tab;

    public AddImportView(ViewContext context, WordRepository wordRepository, WordValidationService validationService,
                         GoalService goalService, AchievementService achievementService,
                         ImportExportService importExportService, SettingsService settingsService,
                         AiCacheRepository aiCacheRepository, EcdictImportService ecdictImportService, LocalDictionaryService localDictionary,
                         ConfiguredServices configured) {
        AddWordBox addWord = new AddWordBox(context, wordRepository, validationService, goalService,
            achievementService, configured);
        EcdictSettingsBox ecdict = new EcdictSettingsBox(context, settingsService, ecdictImportService, localDictionary);
        AiSettingsBox ai = new AiSettingsBox(context, settingsService, aiCacheRepository, configured);
        ImportBox imports = new ImportBox(context, importExportService, goalService, achievementService);

        VBox content = new VBox(24, addWord.root(), ecdict.root(), ai.root(), imports.root());
        content.setPadding(new Insets(24));
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        tab = Widgets.tab("addImportTab", "Add / Import", scrollPane);
    }

    public Tab tab() {
        return tab;
    }
}
