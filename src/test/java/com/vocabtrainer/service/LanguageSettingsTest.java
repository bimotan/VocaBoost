package com.vocabtrainer.service;

import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.SettingsRepository;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.service.LanguageSettings.Language;
import com.vocabtrainer.util.Messages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LanguageSettingsTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    @Test
    void theLanguageFollowsTheComputerUntilOneIsChosen() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("language.db"));
        SettingsService settings = new SettingsService(new SettingsRepository(databaseManager));
        LanguageSettings languages = new LanguageSettings(settings);
        assertEquals(Language.AUTO, languages.language());

        languages.saveLanguage(Language.SIMPLIFIED_CHINESE);
        assertEquals("zh_CN", settings.get(LanguageSettings.LANGUAGE_KEY).orElseThrow());
        assertEquals(Language.SIMPLIFIED_CHINESE, new LanguageSettings(settings).language());

        languages.saveLanguage(Language.ENGLISH);
        assertEquals("en", settings.get(LanguageSettings.LANGUAGE_KEY).orElseThrow());
        assertEquals(Language.ENGLISH, new LanguageSettings(settings).language());

        languages.saveLanguage(Language.AUTO);
        assertEquals("auto", settings.get(LanguageSettings.LANGUAGE_KEY).orElseThrow());
        assertEquals(Language.AUTO, new LanguageSettings(settings).language());
    }

    @Test
    void aSavedValueThatIsNotALanguageFollowsTheComputer() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("bad.db"));
        SettingsService settings = new SettingsService(new SettingsRepository(databaseManager));
        for (String saved : new String[] {"fr", "zh-CN", "", "  "}) {
            settings.save(LanguageSettings.LANGUAGE_KEY, saved);
            assertEquals(Language.AUTO, new LanguageSettings(settings).language(), saved);
        }
        settings.save(LanguageSettings.LANGUAGE_KEY, " zh_CN ");
        assertEquals(Language.SIMPLIFIED_CHINESE, new LanguageSettings(settings).language());
    }

    @Test
    void autoShowsChineseOnAnyChineseComputerAndEnglishOnEveryOther() {
        assertEquals(Messages.SIMPLIFIED_CHINESE, Language.AUTO.locale(Locale.SIMPLIFIED_CHINESE));
        assertEquals(Messages.SIMPLIFIED_CHINESE, Language.AUTO.locale(Locale.TRADITIONAL_CHINESE));
        assertEquals(Messages.SIMPLIFIED_CHINESE, Language.AUTO.locale(Locale.forLanguageTag("zh-SG")));
        assertEquals(Messages.ENGLISH, Language.AUTO.locale(Locale.US));
        assertEquals(Messages.ENGLISH, Language.AUTO.locale(Locale.GERMANY));
        for (Locale computer : new Locale[] {Locale.US, Locale.CHINA}) {
            assertEquals(Messages.SIMPLIFIED_CHINESE, Language.SIMPLIFIED_CHINESE.locale(computer));
            assertEquals(Messages.ENGLISH, Language.ENGLISH.locale(computer));
        }
    }
}
