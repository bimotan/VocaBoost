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
import com.vocabtrainer.service.SettingsService;
import com.vocabtrainer.service.WordValidationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A deck built from the words ECDICT tags with an exam (review finding D3's suggestion). */
class EcdictTagDeckServiceTest {
    private static final String ABATE = "abate,ə'beit,,\"vt. 减少, 减轻\\nvi. 减弱\",,,,gre toefl,9000,8000,,,";
    private static final String LUCID = "lucid,'lu:sid,,\"a. 明晰的, 清醒的\\n[医] 清醒的\",,,,gre,15000,12000,,,";
    /** No frequency rank at all: last when the most common come first. */
    private static final String ABERRANT = "aberrant,æ'berənt,,a. 异常的; 脱离常轨的,,,,gre,0,0,,,";
    /** The most common, but a spelling the app does not take. */
    private static final String AM = "a.m.,,,adv. 上午,,,,gre,100,100,,,";
    /** Tagged "ungre", which is not "gre". */
    private static final String GREGARIOUS = "gregarious,gri'gɛəriəs,,a. 群居的,,,,toefl ungre,20000,20000,,,";

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private EcdictRepository ecdict;
    private DatabaseManager databaseManager;
    private DeckService decks;
    private WordRepository words;
    private EcdictTagDeckService service;

    @BeforeEach
    void setUp() throws SQLException {
        ecdict = new EcdictRepository(tempDir.resolve("ecdict.db"));
        databaseManager = databases.open(tempDir.resolve("vocab.db"));
        decks = new DeckService(new DeckRepository(databaseManager),
            new SettingsService(new SettingsRepository(databaseManager)));
        words = new WordRepository(databaseManager);
        service = new EcdictTagDeckService(ecdict, decks, words, new WordValidationService());
    }

    @AfterEach
    void closeEcdict() {
        ecdict.close();
    }

    @Test
    void withoutAnImportedDictionaryItSaysWhatToDo() {
        assertFalse(service.isAvailable());
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.build(
            request("GRE (ECDICT)", 0, EcdictRepository.TagOrder.FREQUENCY), progress -> { }, () -> false));
        assertTrue(error.getMessage().contains("Import ecdict.csv"), error.getMessage());
        assertTrue(decks.findActiveDeck("GRE (ECDICT)").isEmpty());
    }

    @Test
    void createsADeckOfTheTaggedWordsMostCommonFirstWithCleanMeanings() throws Exception {
        importEcdict();
        List<EcdictTagDeckService.Progress> progress = new ArrayList<>();

        EcdictTagDeckService.Result result = service.build(request("GRE (ECDICT)", 0,
            EcdictRepository.TagOrder.FREQUENCY), progress::add, () -> false);

        assertTrue(result.created());
        assertEquals("GRE (ECDICT)", result.deck().getName());
        assertEquals(4, result.added());
        assertEquals(0, result.alreadyInDeck());
        assertEquals(1, result.skipped(), "a.m. is not a spelling the app takes");
        assertEquals(5, result.tagged());
        assertEquals("Added 4 GRE words to GRE (ECDICT) (new deck). 1 skipped (no Chinese meaning or a spelling with"
            + " characters other than letters, spaces, hyphens and apostrophes).", result.toDisplayText(EcdictTagDeckService.Tag.GRE));
        assertEquals(new EcdictTagDeckService.Progress(0, -1), progress.get(0));
        assertEquals(new EcdictTagDeckService.Progress(4, 4), progress.get(progress.size() - 1));

        // New cards are introduced in the order they were added: the most common first.
        assertEquals(List.of("abandon", "abate", "lucid", "aberrant"), newCardOrder(result.deck()));
        WordCard abandon = words.findByEnglish(result.deck().getId(), "abandon").orElseThrow();
        assertEquals("放弃; 抛弃; 遗弃; 使屈从; 沉溺; 放纵; 放任; 无拘束; 狂热", abandon.getChinese());
        assertEquals("verb; noun", abandon.getPartOfSpeech());
        assertEquals("ә'bændәn", abandon.getPhonetic());
        assertEquals("gre", abandon.getTags());
        WordCard lucid = words.findByEnglish(result.deck().getId(), "lucid").orElseThrow();
        assertEquals("明晰的; 清醒的", lucid.getChinese());
        assertTrue(lucid.getNote().contains("[医] 清醒的"), "tagged senses go to the note: " + lucid.getNote());
        assertTrue(words.findByEnglish(result.deck().getId(), "gregarious").isEmpty(), "tagged ungre, not gre");
        assertEquals(0, count("SELECT COUNT(*) FROM review_logs"), "no reviews");
        assertEquals(0, count("SELECT COUNT(*) FROM daily_goals"), "no XP and no new words counted");
    }

    @Test
    void aLimitCountsTheWordsAddedAndBuildingAgainFillsTheDeckWithTheNextOnes() throws Exception {
        importEcdict();
        EcdictTagDeckService.Result first = service.build(request("GRE", 2, EcdictRepository.TagOrder.FREQUENCY),
            progress -> { }, () -> false);
        assertEquals(List.of("abandon", "abate"), newCardOrder(first.deck()));

        EcdictTagDeckService.Result second = service.build(request(" GRE ", 2, EcdictRepository.TagOrder.FREQUENCY),
            progress -> { }, () -> false);

        assertFalse(second.created(), "the active deck of that name is filled");
        assertEquals(first.deck().getId(), second.deck().getId());
        assertEquals(2, second.added());
        assertEquals(2, second.alreadyInDeck());
        assertEquals(List.of("abandon", "abate", "lucid", "aberrant"), newCardOrder(first.deck()));
        assertEquals("Added 2 GRE words to GRE. 2 already in the deck. 1 skipped (no Chinese meaning or a spelling"
            + " with characters other than letters, spaces, hyphens and apostrophes).",
            second.toDisplayText(EcdictTagDeckService.Tag.GRE));
    }

    @Test
    void alphabeticalOrderAndAnExistingDeckWithSomeOfTheWords() throws Exception {
        importEcdict();
        Deck mine = decks.createDeck("Mine");
        WordCard lucid = WordCard.createNew(mine.getId(), "Lucid", "清楚的");
        lucid.setArchived(true);
        words.insert(lucid);

        EcdictTagDeckService.Result result = service.build(request("Mine", 3, EcdictRepository.TagOrder.ALPHABETICAL),
            progress -> { }, () -> false);

        assertEquals(List.of("abandon", "abate", "aberrant"), newCardOrder(mine));
        assertEquals(0, result.alreadyInDeck(), "lucid comes after the limit");
        EcdictTagDeckService.Result rest = service.build(request("Mine", 0, EcdictRepository.TagOrder.ALPHABETICAL),
            progress -> { }, () -> false);
        assertEquals(0, rest.added());
        assertEquals(4, rest.alreadyInDeck(), "an archived word counts as in the deck");
        assertEquals("清楚的", words.findAllIncludingArchived(mine.getId()).stream()
            .filter(word -> word.getEnglish().equals("Lucid")).findFirst().orElseThrow().getChinese(), "left as it was");
    }

    @Test
    void aSpellingWithDoubledSpacesIsTheWordTheDeckStores() throws Exception {
        String single = "a la carte,,,adv. 照菜单点菜,,,,gre,500,500,,,";
        String doubled = "a  la  carte,,,adv. 按菜单,,,,gre,600,600,,,";
        String inDeck = "en  route,,,adv. 在途中,,,,gre,700,700,,,";
        Path csv = EcdictFixtures.write(tempDir.resolve("ecdict.csv"), false, List.of(single, doubled, inDeck));
        new EcdictImportService(ecdict).importCsv(csv, progress -> { }, () -> false);
        Deck mine = decks.createDeck("Mine");
        words.insert(WordCard.createNew(mine.getId(), "En route", "途中"));

        EcdictTagDeckService.Result result = service.build(request("Mine", 0, EcdictRepository.TagOrder.FREQUENCY),
            progress -> { }, () -> false);

        assertEquals(1, result.added());
        assertEquals(2, result.alreadyInDeck(), "a  la  carte is a la carte, en  route is in the deck");
        assertEquals("照菜单点菜", words.findByEnglish(mine.getId(), "a la carte").orElseThrow().getChinese());
    }

    @Test
    void aCancelAddsNothingNotEvenTheDeck() throws Exception {
        importEcdict();
        int[] checks = {0};

        assertThrows(CancellationException.class, () -> service.build(
            request("GRE (ECDICT)", 0, EcdictRepository.TagOrder.FREQUENCY), progress -> { }, () -> ++checks[0] > 1));

        assertTrue(decks.findActiveDeck("GRE (ECDICT)").isEmpty());
        assertEquals(0, count("SELECT COUNT(*) FROM words"));
    }

    @Test
    void aTagWithoutWordsCreatesNoEmptyDeck() throws Exception {
        importEcdict();

        EcdictTagDeckService.Result result = service.build(new EcdictTagDeckService.Request(
            EcdictTagDeckService.Tag.IELTS, "IELTS (ECDICT)", 0, EcdictRepository.TagOrder.FREQUENCY),
            progress -> { }, () -> false);

        assertEquals(null, result.deck());
        assertEquals(0, result.tagged());
        assertTrue(decks.findActiveDeck("IELTS (ECDICT)").isEmpty());
        assertEquals("No IELTS word could be added, so no deck was created. ECDICT tags no word with ielts.",
            result.toDisplayText(EcdictTagDeckService.Tag.IELTS));
    }

    @Test
    void theRequestNeedsADeckName() {
        assertThrows(IllegalArgumentException.class, () -> request(" ", 0, EcdictRepository.TagOrder.FREQUENCY));
        assertThrows(IllegalArgumentException.class, () -> request("GRE", -1, EcdictRepository.TagOrder.FREQUENCY));
        assertThrows(IllegalArgumentException.class, () -> ecdict.findByTag("gre%", EcdictRepository.TagOrder.FREQUENCY));
        assertEquals("GRE (ECDICT)", EcdictTagDeckService.Tag.GRE.defaultDeckName());
        assertEquals("考研 Kaoyan (ky)", EcdictTagDeckService.Tag.KY.toString());
    }

    private void importEcdict() throws Exception {
        Path csv = EcdictFixtures.write(tempDir.resolve("ecdict.csv"), false,
            List.of(EcdictFixtures.HOOD, EcdictFixtures.A, AM, EcdictFixtures.ABANDON, ABATE, ABERRANT, LUCID,
                GREGARIOUS));
        new EcdictImportService(ecdict).importCsv(csv, progress -> { }, () -> false);
        assertTrue(service.isAvailable());
    }

    private static EcdictTagDeckService.Request request(String deckName, int limit, EcdictRepository.TagOrder order) {
        return new EcdictTagDeckService.Request(EcdictTagDeckService.Tag.GRE, deckName, limit, order);
    }

    private List<String> newCardOrder(Deck deck) throws SQLException {
        return words.findNewCards(deck.getId(), LocalDateTime.now().plusDays(1), 100).stream()
            .map(WordCard::getEnglish)
            .toList();
    }

    private int count(String sql) throws SQLException {
        try (Connection connection = databaseManager.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
