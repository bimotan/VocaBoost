package com.vocabtrainer.ui;

import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.EcdictRepository;
import com.vocabtrainer.service.ecdict.EcdictFixtures;
import com.vocabtrainer.service.ecdict.EcdictImportService;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A word list import and an ECDICT tag deck ask what to do with the words other decks already have
 * (review finding G6): copy their meanings, keep the imported ones, skip them, or cancel.
 */
@Tag("ui")
class OtherDecksImportUiTest extends MainWindowUiTest {
    @Override
    AppServices.Builder configure(AppServices.Builder builder) {
        if (testName().startsWith("anEcdict")) {
            try {
                Path csv = EcdictFixtures.writeGenerated(tempDir.resolve("ecdict.csv"), 5, "词义");
                try (EcdictRepository ecdict = new EcdictRepository(tempDir.resolve("ecdict.db"))) {
                    new EcdictImportService(ecdict).importCsv(csv, progress -> { }, () -> false);
                }
            } catch (Exception e) {
                throw new IllegalStateException("Cannot prepare the ECDICT dictionary", e);
            }
        }
        return builder;
    }

    @Test
    void aWordListWithWordsOfOtherDecksAsksWhetherToCopyTheirMeanings() throws Exception {
        WordCard starterAbate = services.wordRepository().findByEnglish(currentDeck().getId(), "abate").orElseThrow();
        dialogs.answerText("TOEFL");
        click("newDeckButton");
        long toefl = currentDeck().getId();
        Path file = Files.writeString(tempDir.resolve("toefl.csv"),
            "english,chinese\nabate,减轻\nlucid,明白的\npetrichor,雨后的气味\n", StandardCharsets.UTF_8);
        selectTab("addImportTab");
        type("importPathField", file.toString());

        click("previewCsvButton");
        waitForBackgroundTasks();
        assertTrue(text("importStatusLabel").contains("2 words are already in other decks"), text("importStatusLabel"));

        dialogs.chooseButton("Copy their meanings");
        click("importCsvButton");
        waitForBackgroundTasks();

        ScriptedDialogs.Shown question = dialogs.last(ScriptedDialogs.Kind.CHOOSE);
        assertEquals("Words in other decks", question.title());
        assertEquals("2 of the words are already in " + STARTER_DECK + ".", question.header());
        assertEquals(starterAbate.getChinese(),
            services.wordRepository().findByEnglish(toefl, "abate").orElseThrow().getChinese());
        assertEquals("雨后的气味", services.wordRepository().findByEnglish(toefl, "petrichor").orElseThrow().getChinese());
        assertTrue(text("importStatusLabel").contains("Imported 3"), text("importStatusLabel"));
    }

    @Test
    void cancellingTheQuestionImportsNothingAndSkipLeavesThoseWordsOut() throws Exception {
        dialogs.answerText("TOEFL");
        click("newDeckButton");
        long toefl = currentDeck().getId();
        Path file = Files.writeString(tempDir.resolve("toefl.csv"), "english,chinese\nabate,减轻\npetrichor,雨后的气味\n",
            StandardCharsets.UTF_8);
        selectTab("addImportTab");
        type("importPathField", file.toString());

        dialogs.chooseButton("Cancel");
        click("importCsvButton");
        waitForBackgroundTasks();
        assertEquals(0, services.wordRepository().countAll(toefl), "canceled");
        assertEquals("Import canceled. Nothing was imported.", text("importStatusLabel"));

        dialogs.chooseButton("Skip them");
        click("importCsvButton");
        waitForBackgroundTasks();
        assertTrue(services.wordRepository().findByEnglish(toefl, "abate").isEmpty());
        assertTrue(services.wordRepository().findByEnglish(toefl, "petrichor").isPresent());
    }

    @Test
    void aWordListWithoutWordsOfOtherDecksDoesNotAsk() throws Exception {
        Path file = Files.writeString(tempDir.resolve("new.csv"), "english,chinese\npetrichor,雨后的气味\n",
            StandardCharsets.UTF_8);
        selectTab("addImportTab");
        type("importPathField", file.toString());

        click("importCsvButton");
        waitForBackgroundTasks();

        assertFalse(dialogs.wasShown(ScriptedDialogs.Kind.CHOOSE));
        assertTrue(services.wordRepository().findByEnglish(currentDeck().getId(), "petrichor").isPresent());
    }

    @Test
    void anEcdictTagDeckAsksAboutTheWordsOtherDecksHave() throws Exception {
        long starter = currentDeck().getId();
        WordCard mine = WordCard.createNew(starter, "wa", "我的释义");
        mine.setExampleSentence("A sentence with wa in it.");
        services.wordRepository().insert(mine);
        selectTab("decksTab");
        dialogs.submitForm(form -> { }).chooseButton("Copy their meanings");
        click("ecdictDeckButton");
        waitForBackgroundTasks();

        assertEquals("1 of the words is already in " + STARTER_DECK + ".", dialogs.last(ScriptedDialogs.Kind.CHOOSE).header());
        assertEquals("GRE (ECDICT)", currentDeck().getName());
        WordCard copied = services.wordRepository().findByEnglish(currentDeck().getId(), "wa").orElseThrow();
        assertEquals("我的释义", copied.getChinese());
        assertEquals("A sentence with wa in it.", copied.getExampleSentence());
        assertTrue(text("ecdictDeckStatusLabel").contains("1 word has the meaning, part of speech, example and phonetic"
            + " of another deck."), text("ecdictDeckStatusLabel"));
    }

    @Test
    void anEcdictTagDeckCanBeCanceledAtTheQuestion() throws Exception {
        services.wordRepository().insert(WordCard.createNew(currentDeck().getId(), "wa", "我的释义"));
        selectTab("decksTab");
        dialogs.submitForm(form -> ((TextField) form.lookup("#ecdictLimitField")).setText("2")).chooseButton("Cancel");
        click("ecdictDeckButton");
        waitForBackgroundTasks();

        assertEquals(STARTER_DECK, currentDeck().getName(), "no deck was built");
        assertTrue(services.deckService().findActiveDeck("GRE (ECDICT)").isEmpty());
        assertEquals("Canceled. Nothing was added.", text("ecdictDeckStatusLabel"));
    }
}
