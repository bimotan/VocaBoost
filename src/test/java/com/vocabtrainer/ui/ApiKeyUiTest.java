package com.vocabtrainer.ui;

import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.SettingsRepository;
import com.vocabtrainer.service.SettingsService;
import javafx.scene.Node;
import javafx.scene.control.Labeled;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextInputControl;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The AI settings never put a saved API key back on screen. */
@Tag("ui")
class ApiKeyUiTest extends MainWindowUiTest {
    private static final String SAVED_KEY = "sk-saved-0123456789-abcd";
    private static final String NEW_KEY = "sk-new-9876543210-wxyz";

    @Override
    AppServices.Builder configure(AppServices.Builder builder) {
        if (testName().startsWith("aSavedKey")) {
            // A key saved in an earlier run of the app.
            try (DatabaseManager database = new DatabaseManager(tempDir.resolve("vocab.db"))) {
                database.initialize();
                new SettingsService(new SettingsRepository(database))
                    .saveAiSettings("openai-compatible", "https://api.example.com/v1", SAVED_KEY, "model-a");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        return builder;
    }

    @Test
    void aSavedKeyIsNeverPutBackIntoTheFormAndCanBeReplacedOrRemoved() {
        selectTab("addImportTab");

        assertTrue(Fx.call(() -> find("aiApiKeyField", Node.class) instanceof PasswordField));
        assertEquals("", text("aiApiKeyField"), "the saved key is not pre-filled");
        assertFalse(isVisible("aiApiKeyField"));
        assertEquals("Saved, ends with ••••abcd", text("aiKeyStatusLabel"));
        assertTrue(isVisible("replaceAiKeyButton"));
        assertTrue(isVisible("removeAiKeyButton"));
        assertKeyNotOnScreen(SAVED_KEY);

        // Saving other changes keeps the saved key.
        type("aiModelField", "model-b");
        click("saveAiButton");
        assertTrue(text("aiStatusLabel").startsWith("Saved."), text("aiStatusLabel"));
        assertEquals(SAVED_KEY, services.settingsService().getAiApiKey().orElseThrow());

        click("replaceAiKeyButton");
        assertTrue(isVisible("aiApiKeyField"));
        assertEquals("", text("aiApiKeyField"));
        assertFalse(isVisible("replaceAiKeyButton"));
        type("aiApiKeyField", NEW_KEY);
        click("saveAiButton");

        assertEquals(NEW_KEY, services.settingsService().getAiApiKey().orElseThrow());
        assertEquals("Saved, ends with ••••wxyz", text("aiKeyStatusLabel"));
        assertEquals("", text("aiApiKeyField"), "the typed key is cleared once saved");
        assertFalse(isVisible("aiApiKeyField"));
        assertKeyNotOnScreen(NEW_KEY);

        dialogs.confirm(true);
        click("removeAiKeyButton");

        assertTrue(services.settingsService().getAiApiKey().isEmpty());
        assertEquals("model-b", services.settingsService().getAiModel().orElseThrow());
        assertTrue(isVisible("aiApiKeyField"));
        assertFalse(isVisible("aiKeyStatusLabel"));
        assertFalse(isVisible("removeAiKeyButton"));
        assertTrue(text("aiStatusLabel").startsWith("API key removed."), text("aiStatusLabel"));
    }

    @Test
    void aTypedKeyIsSavedAndThenOnlyShownByItsLastCharacters() {
        selectTab("addImportTab");
        assertTrue(isVisible("aiApiKeyField"));
        assertFalse(isVisible("aiKeyStatusLabel"));
        assertFalse(isVisible("replaceAiKeyButton"));

        type("aiBaseUrlField", "https://api.example.com");
        type("aiApiKeyField", NEW_KEY);
        type("aiModelField", "model-a");
        click("saveAiButton");

        assertEquals(NEW_KEY, services.settingsService().getAiApiKey().orElseThrow());
        assertEquals("", text("aiApiKeyField"));
        assertFalse(isVisible("aiApiKeyField"));
        assertEquals("Saved, ends with ••••wxyz", text("aiKeyStatusLabel"));
        assertKeyNotOnScreen(NEW_KEY);
    }

    @Test
    void plainHttpToAnotherComputerIsRefusedWhenSaving() {
        selectTab("addImportTab");
        type("aiBaseUrlField", "http://api.example.com/v1");
        type("aiApiKeyField", NEW_KEY);
        type("aiModelField", "model-a");
        click("saveAiButton");

        assertEquals("The AI base URL uses plain http, which would send the API key unencrypted. Use https, or"
            + " http only for a server on this computer (localhost, 127.0.0.1 or ::1).", text("aiStatusLabel"));
        assertTrue(services.settingsService().getAiApiKey().isEmpty());
    }

    /** No label or text field anywhere in the window shows {@code key}, even partly. */
    private void assertKeyNotOnScreen(String key) {
        String middle = key.substring(3, key.length() - 4);
        List<String> ids = allIds();
        for (String id : ids) {
            String shown = Fx.call(() -> {
                Node node = find(id, Node.class);
                return node instanceof Labeled labeled ? labeled.getText()
                    : node instanceof TextInputControl input ? input.getText() : "";
            });
            assertFalse(shown != null && shown.contains(middle), "#" + id + " shows the key: " + shown);
        }
    }
}
