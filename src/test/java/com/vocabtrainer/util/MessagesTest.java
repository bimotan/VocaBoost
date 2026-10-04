package com.vocabtrainer.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesTest {
    @AfterEach
    void backToEnglish() {
        Messages.setLocale(Messages.ENGLISH);
    }

    @Test
    void textsAreEnglishUntilAnotherLanguageIsChosen() {
        assertEquals(Messages.ENGLISH, Messages.locale());
        assertFalse(Messages.isChinese());
        assertEquals("Again", Messages.tr("rating.again"));
        assertEquals("Not found: Wiktionary does not have this word.", Messages.tr("dictionary.notFoundIn", "Wiktionary"));
    }

    @Test
    void anyChineseLocaleShowsSimplifiedChineseAndEveryOtherEnglish() {
        for (Locale chinese : List.of(Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE, Locale.CHINESE,
            Locale.forLanguageTag("zh-HK"))) {
            Messages.setLocale(chinese);
            assertEquals(Messages.SIMPLIFIED_CHINESE, Messages.locale(), chinese.toString());
            assertTrue(Messages.isChinese());
            assertEquals("重来", Messages.tr("rating.again"));
        }
        for (Locale other : List.of(Locale.FRENCH, Locale.JAPANESE, Locale.US, Locale.ROOT)) {
            Messages.setLocale(other);
            assertEquals(Messages.ENGLISH, Messages.locale(), other.toString());
            assertEquals("Again", Messages.tr("rating.again"));
        }
    }

    @Test
    void argumentsAreFilledInWithNumbersAndPluralsOfTheLanguage() {
        assertEquals("1 day", Messages.tr("format.days", 1));
        assertEquals("2 days", Messages.tr("format.days", 2));
        assertEquals("Imported 1,240, skipped 3.", Messages.tr("import.result.counts", 1240, 3));
        assertEquals("Unsupported backup version 9; this VocaBoost reads versions 1 to 2.",
            Messages.tr("backup.error.version", "9", 2));

        Messages.setLocale(Messages.SIMPLIFIED_CHINESE);
        assertEquals("2 天", Messages.tr("format.days", 2));
        assertEquals("已导入 1,240 个，跳过 3 个。", Messages.tr("import.result.counts", 1240, 3));
        assertEquals("词条未找到：Wiktionary 没有该词条。", Messages.tr("dictionary.notFoundIn", "Wiktionary"));
    }

    @Test
    void aTextCanBeShownInAnotherLanguageThanTheAppsOne() {
        assertEquals("重新启动 VocaBoost 后将使用新的语言。", Messages.trIn(Locale.CHINA, "settings.language.restart"));
        assertEquals("The new language is used when you start VocaBoost again.",
            Messages.trIn(Locale.UK, "settings.language.restart"));
        assertEquals(Messages.ENGLISH, Messages.locale(), "the app's language stays");
    }

    @Test
    void sentencesAreJoinedWithASpaceInEnglishAndWithoutOneInChinese() {
        assertEquals("Saved. XP +5", Messages.sentences(List.of("Saved.", "", "XP +5")));
        Messages.setLocale(Messages.SIMPLIFIED_CHINESE);
        assertEquals("已保存。经验值 +5", Messages.sentences(List.of("已保存。", "经验值 +5")));
        assertEquals("", Messages.sentences(List.of()));
    }

    @Test
    void aChineseTextWithoutArgumentsCanBeLoggedInEnglish() {
        assertEquals("无法保存目标", Messages.inEnglish("无法保存目标"), "the app is in English: nothing to do");
        Messages.setLocale(Messages.SIMPLIFIED_CHINESE);
        assertEquals("Goals not saved", Messages.inEnglish(Messages.tr("goals.notSaved")));
        assertEquals("Importing...", Messages.inEnglish(Messages.tr("import.running")));
        assertEquals("核对 lucid 失败", Messages.inEnglish(Messages.tr("add.check.failed.title", "lucid")),
            "a text with arguments stays as it is");
        assertEquals("anything else", Messages.inEnglish("anything else"));
        assertEquals(null, Messages.inEnglish(null));
    }

    @Test
    void aMissingKeyIsShownAsItIs() {
        assertEquals("no.such.key", Messages.tr("no.such.key", 1));
    }
}
