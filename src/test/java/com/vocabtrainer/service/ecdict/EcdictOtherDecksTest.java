package com.vocabtrainer.service.ecdict;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.EcdictRepository;
import com.vocabtrainer.repository.SettingsRepository;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.DeckService;
import com.vocabtrainer.service.InOtherDecks;
import com.vocabtrainer.service.SettingsService;
import com.vocabtrainer.service.WordValidationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An ECDICT tag deck and the words other decks already have (review finding G6). */
class EcdictOtherDecksTest {
    private static final String ABATE = "abate,ə'beit,,\"vt. 减少, 减轻\\nvi. 减弱\",,,,gre,9000,8000,,,";
    private static final String LUCID = "lucid,'lu:sid,,a. 明晰的,,,,gre,15000,12000,,,";
    private static final String ABERRANT = "aberrant,æ'berənt,,a. 异常的,,,,gre,0,0,,,";

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private EcdictRepository ecdict;
    private DeckService decks;
    private WordRepository words;
    private EcdictTagDeckService service;
    private Deck mine;

    @BeforeEach
    void setUp() throws Exception {
        ecdict = new EcdictRepository(tempDir.resolve("ecdict.db"));
        DatabaseManager databaseManager = databases.open(tempDir.resolve("vocab.db"));
        decks = new DeckService(new DeckRepository(databaseManager),
            new SettingsService(new SettingsRepository(databaseManager)));
        words = new WordRepository(databaseManager);
        service = new EcdictTagDeckService(ecdict, decks, words, new WordValidationService());
        Path csv = EcdictFixtures.write(tempDir.resolve("ecdict.csv"), false, List.of(ABATE, LUCID, ABERRANT));
        new EcdictImportService(ecdict).importCsv(csv, progress -> { }, () -> false);
        mine = decks.createDeck("Mine");
        WordCard abate = WordCard.createNew(mine.getId(), "abate", "我的释义");
        abate.setExampleSentence("The storm had begun to abate.");
        abate.setPartOfSpeech("verb");
        words.insert(abate);
        words.insert(WordCard.createNew(mine.getId(), "Lucid", "清楚的"));
    }

    @AfterEach
    void closeEcdict() {
        ecdict.close();
    }

    @Test
    void theWordsToAddThatOtherDecksHaveAreCountedBeforehand() {
        EcdictTagDeckService.OtherDecks counted = service.countInOtherDecks(request(0, InOtherDecks.KEEP_IMPORTED));

        assertEquals(2, counted.words());
        assertEquals(List.of(mine.getId()), counted.deckIds());
        assertTrue(decks.findActiveDeck("GRE (ECDICT)").isEmpty(), "counting writes nothing");
    }

    @Test
    void keptWordsHaveEcdictsMeaning() throws SQLException {
        EcdictTagDeckService.Result result = service.build(request(0, InOtherDecks.KEEP_IMPORTED), p -> { }, () -> false);

        assertEquals(3, result.added());
        assertEquals("减少; 减轻; 减弱", word(result.deck(), "abate").getChinese());
    }

    @Test
    void copiedWordsHaveTheOtherDecksMeaningAndExample() throws SQLException {
        EcdictTagDeckService.Result result = service.build(request(0, InOtherDecks.COPY_DETAILS), p -> { }, () -> false);

        WordCard abate = word(result.deck(), "abate");
        assertEquals("我的释义", abate.getChinese());
        assertEquals("The storm had begun to abate.", abate.getExampleSentence());
        assertEquals("verb", abate.getPartOfSpeech());
        assertEquals("gre", abate.getTags(), "the tags stay the build's");
        assertEquals("清楚的", word(result.deck(), "lucid").getChinese());
        assertEquals(3, result.added());
        assertTrue(result.toDisplayText(EcdictTagDeckService.Tag.GRE).contains(
            "2 words have the meaning, part of speech, example and phonetic of another deck."),
            result.toDisplayText(EcdictTagDeckService.Tag.GRE));
    }

    @Test
    void skippedWordsAreLeftOutAndDoNotCountTowardsTheLimit() throws SQLException {
        EcdictTagDeckService.Result result = service.build(request(1, InOtherDecks.SKIP), p -> { }, () -> false);

        assertEquals(1, result.added());
        assertEquals(2, result.inOtherDecks());
        assertEquals("aberrant", words.findAll(result.deck().getId()).get(0).getEnglish());
        assertTrue(result.toDisplayText(EcdictTagDeckService.Tag.GRE).contains("2 skipped: already in other decks."),
            result.toDisplayText(EcdictTagDeckService.Tag.GRE));
    }

    private WordCard word(Deck deck, String english) throws SQLException {
        return words.findByEnglish(deck.getId(), english).orElseThrow();
    }

    private static EcdictTagDeckService.Request request(int limit, InOtherDecks choice) {
        return new EcdictTagDeckService.Request(EcdictTagDeckService.Tag.GRE, "GRE (ECDICT)", limit,
            EcdictRepository.TagOrder.FREQUENCY, choice);
    }
}
