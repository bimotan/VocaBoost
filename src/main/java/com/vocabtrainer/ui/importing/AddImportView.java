package com.vocabtrainer.ui.importing;

import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.ImportExportService;
import com.vocabtrainer.service.SettingsService;
import com.vocabtrainer.service.WordValidationService;
import com.vocabtrainer.ui.ConfiguredServices;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import javafx.geometry.Insets;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.layout.VBox;

/**
 * The Add / Import tab: manual add with dictionary lookup, file imports and exports for other apps.
 * The dictionary and AI settings are on the Settings tab.
 */
public final class AddImportView {
    private final Tab tab;

    public AddImportView(ViewContext context, WordRepository wordRepository, WordValidationService validationService,
                         ImportExportService importExportService, SettingsService settingsService,
                         ConfiguredServices configured) {
        AddWordBox addWord = new AddWordBox(context, wordRepository, validationService, configured);
        ImportBox imports = new ImportBox(context, importExportService, settingsService);
        WordListExportBox exports = new WordListExportBox(context, importExportService);

        VBox content = new VBox(24, addWord.root(), imports.root(), exports.root());
        content.setPadding(new Insets(24));
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        tab = Widgets.tab("addImportTab", "Add / Import", scrollPane);
    }

    public Tab tab() {
        return tab;
    }
}
