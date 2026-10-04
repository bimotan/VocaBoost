package com.vocabtrainer.service.ecdict;

import com.vocabtrainer.TestClock;
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
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /** Dates the decks and words. */
    private final TestClock clock = new TestClock(LocalDateTime.of(2026, 3, 10, 10, 0));
    private EcdictRepository ecdict;
    private DeckService decks;
    private WordRepository words;
    private EcdictTagDeckService service;
    private Deck mine;

    @BeforeEach
    void setUp() throws Exception {
        ecdict = new EcdictRepository(tempDir.resolve("ecdict.db"));
        DatabaseManager databaseManager = databases.open(tempDir.resolve("vocab.db"));
        decks = new DeckService(new DeckRepository(databaseManager, clock),
            new SettingsService(new SettingsRepository(databaseManager)));
        words = new WordRepository(databaseManager);
        service = new EcdictTagDeckService(ecdict, decks, words, new WordValidationService(), clock);
        Path csv = EcdictFixtures.write(tempDir.resolve("ecdict.csv"), false, List.of(ABATE, LUCID, ABERRANT));
        new EcdictImportService(ecdict).importCsv(csv, progress -> { }, () -> false);
        mine = decks.createDeck("Mine");
        WordCard abate = WordCard.createNew(mine.getId(), "abate", "我的释义", clock.now());
        abate.setExampleSentence("The storm had begun to abate.");
        abate.setPartOfSpeech("verb");
        words.insert(abate);
        words.insert(WordCard.createNew(mine.getId(), "Lucid", "清楚的", clock.now()));
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

    @Test
    void theChoiceHoldsInEveryBatchAndACanceledBuildCarriesIt() throws Exception {
        try (EcdictRepository generated = new EcdictRepository(tempDir.resolve("generated.db"))) {
            Path csv = EcdictFixtures.writeGenerated(tempDir.resolve("generated.csv"), 600, "词义");
            new EcdictImportService(generated).importCsv(csv, progress -> { }, () -> false);
            EcdictTagDeckService batched = new EcdictTagDeckService(generated, decks, words, new WordValidationService(),
                clock);
            String first = EcdictFixtures.generatedWord(0);
            String later = EcdictFixtures.generatedWord(400);
            words.insert(WordCard.createNew(mine.getId(), first, "我的第一个", clock.now()));
            words.insert(WordCard.createNew(mine.getId(), later, "我的后一个", clock.now()));
            int[] checks = {0};

            // Checked after reading ECDICT, then before each batch: the second batch is not written.
            EcdictTagDeckService.Result canceled = batched.build(request(0, InOtherDecks.SKIP), p -> { },
                () -> ++checks[0] > 2);

            assertTrue(canceled.canceled());
            assertEquals(EcdictTagDeckService.BATCH_SIZE, canceled.added(), "a skipped word leaves its place to the next");
            assertEquals(2, canceled.inOtherDecks());
            assertEquals(InOtherDecks.SKIP, canceled.choice());
            assertTrue(words.findByEnglish(canceled.deck().getId(), first).isEmpty());
            assertEquals("Canceled after adding 250 GRE words to GRE (ECDICT). Building the deck again adds the rest.",
                canceled.toDisplayText(EcdictTagDeckService.Tag.GRE));

            EcdictTagDeckService.Result rest = batched.build(request(0, InOtherDecks.COPY_DETAILS), p -> { },
                () -> false);

            assertFalse(rest.canceled());
            assertEquals(canceled.deck().getId(), rest.deck().getId());
            assertEquals(600 - EcdictTagDeckService.BATCH_SIZE, rest.added());
            assertEquals(EcdictTagDeckService.BATCH_SIZE, rest.alreadyInDeck());
            assertEquals(2, rest.inOtherDecks());
            assertEquals("我的第一个", word(rest.deck(), first).getChinese());
            assertEquals("我的后一个", word(rest.deck(), later).getChinese(), "copied in a later batch too");
            assertTrue(rest.toDisplayText(EcdictTagDeckService.Tag.GRE).contains("250 already in the deck. 2 words have"
                + " the meaning, part of speech, example and phonetic of another deck."),
                rest.toDisplayText(EcdictTagDeckService.Tag.GRE));
        }
    }

    private WordCard word(Deck deck, String english) throws SQLException {
        return words.findByEnglish(deck.getId(), english).orElseThrow();
    }

    private static EcdictTagDeckService.Request request(int limit, InOtherDecks choice) {
        return new EcdictTagDeckService.Request(EcdictTagDeckService.Tag.GRE, "GRE (ECDICT)", limit,
            EcdictRepository.TagOrder.FREQUENCY, choice);
    }
}
