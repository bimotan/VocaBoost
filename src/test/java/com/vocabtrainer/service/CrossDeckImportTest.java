package com.vocabtrainer.service;

import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.TestDatabases;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A word list import and the words other decks already have (review finding G6): counted in the
 * preview, then imported as the file has them, with the other deck's details, or skipped.
 */
class CrossDeckImportTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private AppServices services;
    private ImportExportService imports;
    private long starter;
    private Deck toefl;
    private Path file;

    @BeforeEach
    void setUp() throws Exception {
        services = AppServices.builder(tempDir.resolve("vocab.db")).open();
        databases.track(services.databaseManager());
        imports = services.importExportService();
        starter = services.startupDeck().getId();
        toefl = services.deckService().createDeck("TOEFL");
        file = Files.writeString(tempDir.resolve("list.csv"), String.join("\n",
            "english,chinese,example",
            "abate,减轻,",
            "lucid,明白的,A lucid answer.",
            "petrichor,雨后的气味,"), StandardCharsets.UTF_8);
    }

    @Test
    void thePreviewCountsTheWordsOtherDecksHave() {
        ImportPreview preview = imports.previewWordList(file, toefl.getId(), WordListOptions.DETECT);

        assertEquals(2, preview.inOtherDecks());
        assertEquals(List.of(starter), preview.otherDeckIds());
        assertTrue(preview.toSummary().contains("2 words are already in other decks"), preview.toSummary());
        assertEquals(0, imports.previewWordList(file, starter, WordListOptions.DETECT).inOtherDecks(),
            "the deck's own words are duplicates, not words of another deck");
    }

    @Test
    void keptWordsAreImportedAsTheFileHasThem() throws Exception {
        ImportResult result = imports.importWordList(file, toefl.getId(), WordListOptions.DETECT, p -> { }, () -> false);

        assertEquals(3, result.importedCount());
        assertEquals("减轻", word("abate").getChinese());
        assertEquals("Imported 3, skipped 0.", result.toSummary());
    }

    @Test
    void copiedWordsGetTheOtherDecksMeaningPartOfSpeechAndExample() throws Exception {
        WordCard starterAbate = services.wordRepository().findByEnglish(starter, "abate").orElseThrow();

        ImportResult result = imports.importWordList(file, toefl.getId(),
            WordListOptions.DETECT.with(InOtherDecks.COPY_DETAILS), p -> { }, () -> false);

        assertEquals(3, result.importedCount());
        WordCard abate = word("abate");
        assertEquals(starterAbate.getChinese(), abate.getChinese());
        assertEquals(starterAbate.getExampleSentence(), abate.getExampleSentence());
        assertEquals("verb", abate.getPartOfSpeech());
        assertEquals("雨后的气味", word("petrichor").getChinese(), "a word no other deck has stays as it is");
        assertTrue(result.toSummary().contains("2 words were imported with the meaning, part of speech, example and"
            + " phonetic of another deck."), result.toSummary());
    }

    @Test
    void skippedWordsAreNotImported() throws Exception {
        ImportResult result = imports.importWordList(file, toefl.getId(),
            WordListOptions.DETECT.with(InOtherDecks.SKIP), p -> { }, () -> false);

        assertEquals(1, result.importedCount());
        assertEquals(2, result.skippedCount());
        assertTrue(result.messages().contains("Line 2 skipped: abate is already in another deck."), result.messages().toString());
        assertTrue(services.wordRepository().findByEnglish(toefl.getId(), "abate").isEmpty());
        assertEquals(List.of("petrichor"), services.wordRepository().findAll(toefl.getId()).stream()
            .map(WordCard::getEnglish).toList());
    }

    @Test
    void aWordOnlyListCopiesMeaningsFromOtherDecksWithoutADictionary() throws Exception {
        Path words = Files.writeString(tempDir.resolve("words.txt"), "abate\nlucid\n", StandardCharsets.UTF_8);
        WordCard starterLucid = services.wordRepository().findByEnglish(starter, "lucid").orElseThrow();

        ImportResult result = imports.importWordList(words, toefl.getId(),
            WordListOptions.DETECT.with(InOtherDecks.COPY_DETAILS), p -> { }, () -> false);

        assertEquals(2, result.importedCount());
        assertEquals(starterLucid.getChinese(), word("lucid").getChinese());
        assertEquals(0, result.meaningsFilled(), "copied, not looked up");
    }

    private WordCard word(String english) throws Exception {
        return services.wordRepository().findByEnglish(toefl.getId(), english).orElseThrow();
    }
}
