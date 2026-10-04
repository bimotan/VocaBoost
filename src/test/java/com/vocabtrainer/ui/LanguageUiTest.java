package com.vocabtrainer.ui;

import com.vocabtrainer.service.LanguageSettings;
import com.vocabtrainer.service.LanguageSettings.Language;
import com.vocabtrainer.util.Messages;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The language of the window: Auto (the computer's language), 简体中文 or English on the Settings tab,
 * saved at once and used from the next start. Takes a snapshot of every tab in both languages.
 */
@Tag("ui")
class LanguageUiTest extends MainWindowUiTest {
    private static final List<String> TABS =
        List.of("dashboardTab", "decksTab", "reviewTab", "addImportTab", "statisticsTab", "wordListTab", "settingsTab");
    private static final String AUTO_NOTE_ENGLISH = "Auto follows the language of your computer: Simplified Chinese"
        + " on a Chinese system, English otherwise.";
    private static final String AUTO_NOTE_CHINESE = "“自动”跟随电脑的语言：中文系统显示简体中文，其他系统显示英文。";
    private static final String RESTART_ENGLISH = "The new language is used when you start VocaBoost again.";
    private static final String RESTART_CHINESE = "重新启动 VocaBoost 后将使用新的语言。";

    @Override
    Locale systemLocale() {
        return testName().startsWith("onAChineseComputer") ? Locale.TRADITIONAL_CHINESE : super.systemLocale();
    }

    @Test
    void simplifiedChineseIsSavedAtOnceAndShownFromTheNextStart() throws SQLException {
        assertEnglishWindow();
        selectTab("settingsTab");
        assertEquals("Auto (English)", shownValue("languageSelector"));
        assertEquals(AUTO_NOTE_ENGLISH, text("languageNoteLabel"));
        assertEquals(List.of(Language.AUTO, Language.SIMPLIFIED_CHINESE, Language.ENGLISH),
            Fx.call(() -> List.copyOf(this.<Language>comboBox("languageSelector").getItems())));
        snapshotEveryTab("en");

        selectLanguage(Language.SIMPLIFIED_CHINESE);
        assertEquals("简体中文", shownValue("languageSelector"));
        assertEquals(RESTART_CHINESE, text("languageNoteLabel"), "said in the chosen language");
        assertEquals("zh_CN", services.settingsService().get(LanguageSettings.LANGUAGE_KEY).orElseThrow());
        assertEquals("Settings", Fx.call(() -> tab("settingsTab").getText()), "the window stays as it is until then");

        restartApp();

        assertChineseWindow();
        selectTab("settingsTab");
        assertEquals("简体中文", shownValue("languageSelector"));
        assertEquals(AUTO_NOTE_CHINESE, text("languageNoteLabel"));
        snapshotEveryTab("zh");

        selectLanguage(Language.ENGLISH);
        assertEquals("English", shownValue("languageSelector"));
        assertEquals(RESTART_ENGLISH, text("languageNoteLabel"));
        restartApp();
        assertEnglishWindow();
    }

    @Test
    void onAChineseComputerAutoShowsChineseAndEnglishCanBeChosen() {
        assertChineseWindow();
        assertEquals("默认词库", currentDeck().getName(), "a new database's deck is named in the app's language");
        selectTab("settingsTab");
        assertEquals("自动（简体中文）", shownValue("languageSelector"));
        assertEquals(AUTO_NOTE_CHINESE, text("languageNoteLabel"));

        selectLanguage(Language.ENGLISH);
        assertEquals(RESTART_ENGLISH, text("languageNoteLabel"));
        restartApp();

        assertEnglishWindow();
        assertEquals("默认词库", currentDeck().getName(), "the deck keeps its name, which is the user's");
        selectTab("settingsTab");
        assertEquals("English", shownValue("languageSelector"));
        selectLanguage(Language.AUTO);
        assertEquals("Auto (简体中文)", shownValue("languageSelector"));
        assertEquals(RESTART_CHINESE, text("languageNoteLabel"));
        restartApp();
        assertChineseWindow();
    }

    /** The Chinese window at the smallest size it can have: every label and button keeps its text. */
    @Test
    void theChineseWindowFitsTheSmallestWindow() {
        selectTab("settingsTab");
        selectLanguage(Language.SIMPLIFIED_CHINESE);
        restartApp();
        resizeWindow(960, 640);
        for (String tab : List.of("dashboardTab", "reviewTab", "addImportTab", "wordListTab", "settingsTab")) {
            selectTab(tab);
            Fx.flush();
            snapshot("zh-small-" + tab.replace("Tab", ""));
        }
        selectTab("reviewTab");
        for (String button : List.of("rateAgainButton", "rateHardButton", "rateGoodButton", "rateEasyButton",
            "submitAnswerButton")) {
            assertTrue(isEntirelyInWindow(button), button + " is cut off");
        }
    }

    private void assertEnglishWindow() {
        assertEquals(Messages.ENGLISH, Messages.locale());
        assertEquals(Locale.ENGLISH, Locale.getDefault(), "JavaFX's own texts, such as dialog buttons, follow it");
        assertEquals(List.of("Dashboard", "Decks", "Review", "Add / Import", "Statistics", "Word List", "Settings"),
            tabTitles());
        assertEquals("New deck", text("newDeckButton"));
        assertTrue(headerSubtitle().startsWith("Deck: "), headerSubtitle());
        assertTrue(headerSubtitle().contains(" | Dictionary: starter/online fallback | AI: mock"), headerSubtitle());
        assertEquals("Submit", text("submitAnswerButton"));
        assertEquals("English → Chinese", shownValue("reviewModeSelector"));
        assertTrue(text("rateAgainButton").startsWith("Again (1)"), text("rateAgainButton"));
        assertEquals("Set exam date", text("editExamButton"));
        assertEquals("All", shownValue("wordStatusFilter"));
    }

    /** The header and a few controls of every tab show Chinese. */
    private void assertChineseWindow() {
        assertEquals(Messages.SIMPLIFIED_CHINESE, Messages.locale());
        assertEquals(Locale.SIMPLIFIED_CHINESE, Locale.getDefault(), "JavaFX's own texts, such as dialog buttons, follow it");
        assertEquals(List.of("概览", "词库", "复习", "添加 / 导入", "统计", "单词列表", "设置"), tabTitles());
        assertEquals("新建词库", text("newDeckButton"));
        assertEquals("重命名", text("renameDeckButton"));
        assertEquals("离线模式", text("offlineModeToggle"));
        assertTrue(headerSubtitle().startsWith("词库："), headerSubtitle());
        assertTrue(headerSubtitle().contains(" | 词典：内置词表 / 在线词典 | AI：示例"), headerSubtitle());

        selectTab("dashboardTab");
        assertEquals("编辑目标", text("editGoalsButton"));
        assertEquals("设置考试日期", text("editExamButton"));
        assertEquals("刷新", text("refreshDashboardButton"));

        selectTab("decksTab");
        assertEquals(List.of("词库", "单词数", "到期", "最近复习"), columnTitles("deckTable"));
        assertEquals("切换到所选词库", text("switchDeckButton"));

        selectTab("reviewTab");
        assertEquals("英译中", shownValue("reviewModeSelector"));
        assertEquals("提交", text("submitAnswerButton"));
        assertEquals("重置本轮", text("resetSessionButton"));
        assertTrue(text("rateAgainButton").startsWith("重来 (1)"), text("rateAgainButton"));
        assertTrue(text("rateHardButton").startsWith("困难 (2)"), text("rateHardButton"));
        assertTrue(text("rateGoodButton").startsWith("良好 (3)"), text("rateGoodButton"));
        assertTrue(text("rateEasyButton").startsWith("简单 (4)"), text("rateEasyButton"));
        assertTrue(text("sessionProgressLabel").startsWith("本轮 0/"), text("sessionProgressLabel"));

        selectTab("addImportTab");
        assertEquals("添加单词", text("addWordButton"));
        assertEquals("在线查词", text("lookupButton"));
        assertEquals("导出为 Anki 文件 (TSV)", text("exportAnkiButton"));

        selectTab("statisticsTab");
        assertEquals("未来 14 天", shownValue("forecastRangeSelector"));
        assertEquals("刷新统计", text("refreshStatisticsButton"));

        selectTab("wordListTab");
        assertEquals("全部", shownValue("wordStatusFilter"));
        assertTrue(columnTitles("wordTable").containsAll(List.of("下次复习", "间隔", "记忆")), columnTitles("wordTable").toString());
        assertEquals("编辑所选", text("editWordButton"));

        selectTab("settingsTab");
        assertEquals("保存 AI 设置", text("saveAiButton"));
        assertEquals("打开数据文件夹", text("openDataFolderButton"));
        assertEquals("100%（系统大小）", shownValue("textSizeSelector"));
    }

    private void selectLanguage(Language language) {
        this.<Language>select("languageSelector", language::equals);
    }

    private List<String> tabTitles() {
        return Fx.call(() -> find("mainTabs", TabPane.class).getTabs().stream().map(tab -> tab.getText()).toList());
    }

    private List<String> columnTitles(String tableId) {
        return Fx.call(() -> table(tableId).getColumns().stream().map(TableColumn::getText).toList());
    }

    /**
     * Every tab, with a checked answer on the Review tab so that its rating buttons show their
     * intervals, and the end of the Settings tab.
     */
    private void snapshotEveryTab(String language) throws SQLException {
        for (String tab : TABS) {
            selectTab(tab);
            if (tab.equals("reviewTab")) {
                type("answerField", correctAnswer(questionWord()));
                click("submitAnswerButton");
                waitForBackgroundTasks();
            }
            Fx.flush();
            snapshot(language + "-" + tab.replace("Tab", ""));
        }
        // The language section, at the end of the Settings tab.
        Fx.run(() -> ((ScrollPane) tab("settingsTab").getContent()).setVvalue(1));
        Fx.flush();
        snapshot(language + "-settings-end");
    }
}
