package com.vocabtrainer.repository;

import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.ValidatedWord;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.util.DateTimeUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The words table. A suspended word ({@link WordCard#isSuspended()}, column {@code archived}) is in
 * no review queue, due count, weak list or mastered count: every such query filters
 * {@code archived = 0}. The Word List ({@link #search}), backups and the CSV export
 * ({@link #findAllIncludingSuspended}), duplicate checks ({@link #findByEnglish}) and the deck's
 * word count ({@link #countAll}) include it.
 */
public class WordRepository {
    /** At most this many ids are bound in one {@code IN} list. */
    private static final int IDS_PER_STATEMENT = 500;
    /**
     * The words {@link WordCard#isDue} calls due: due before the end of the study day and, for a
     * learning or relearning card, due by now. Binds the end of the study day, then now.
     */
    private static final String DUE = """
        next_review_at < ? AND (next_review_at <= ? OR COALESCE(card_state, 'NEW') NOT IN ('LEARNING', 'RELEARNING'))
        """;
    /**
     * The words {@link WordCard#isWeak} calls weak. Binds {@link WordCard#RECENT_REVIEWS},
     * {@link WordCard#WEAK_DIFFICULTY} and {@link WordCard#MASTERED_STABILITY_DAYS}.
     */
    private static final String WEAK = """
        (card_state = 'RELEARNING'
         OR (repetitions > consecutive_correct AND consecutive_correct < ?)
         OR (difficulty >= ? AND NOT (COALESCE(card_state, 'NEW') = 'REVIEW' AND stability >= ?)))
        """;
    private static final String COLUMNS = """
        deck_id, english, chinese, phonetic, part_of_speech, example_sentence, note, tags, added_at,
        last_reviewed_at, next_review_at, easiness_factor, interval_days, repetitions, consecutive_correct,
        lapses, archived, card_state, stability, difficulty, learning_step
        """;
    private static final String INSERT = "INSERT INTO words(" + COLUMNS + ") VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
        + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final int BOUND_COLUMNS = 21;

    private final DatabaseManager databaseManager;

    public WordRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /** Runs several writes on this repository's database (and others sharing it) atomically. */
    public TransactionRunner transactions() {
        return databaseManager;
    }

    public WordCard save(WordCard word) throws SQLException {
        if (word.getId() == 0) {
            return insert(word);
        }
        update(word);
        return word;
    }

    public WordCard insert(WordCard word) throws SQLException {
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {
            bindWord(statement, word);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    word.setId(keys.getLong(1));
                }
            }
        }
        return word;
    }

    public int insertAll(List<WordCard> words) throws SQLException {
        if (words == null || words.isEmpty()) {
            return 0;
        }
        // All rows or none; joins the caller's transaction if there is one.
        return databaseManager.inTransaction(() -> {
            try (Connection connection = databaseManager.getConnection();
                 PreparedStatement statement = connection.prepareStatement(INSERT)) {
                int inserted = 0;
                for (WordCard word : words) {
                    bindWord(statement, word);
                    statement.executeUpdate();
                    inserted++;
                }
                return inserted;
            }
        });
    }

    public void update(WordCard word) throws SQLException {
        String sql = """
            UPDATE words
            SET deck_id = ?, english = ?, chinese = ?, phonetic = ?, part_of_speech = ?, example_sentence = ?,
                note = ?, tags = ?, added_at = ?, last_reviewed_at = ?, next_review_at = ?, easiness_factor = ?,
                interval_days = ?, repetitions = ?, consecutive_correct = ?, lapses = ?, archived = ?,
                card_state = ?, stability = ?, difficulty = ?, learning_step = ?
            WHERE id = ?
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindWord(statement, word);
            statement.setLong(BOUND_COLUMNS + 1, word.getId());
            statement.executeUpdate();
        }
    }

    /**
     * Saves the word's text: English, Chinese, phonetic, part of speech, example, note and tags. Its
     * deck and review schedule are not written, so an edit can never put back a schedule older than
     * the one stored. Returns false when there is no word with this id.
     */
    public boolean updateText(long id, ValidatedWord text) throws SQLException {
        String sql = """
            UPDATE words
            SET english = ?, chinese = ?, phonetic = ?, part_of_speech = ?, example_sentence = ?, note = ?, tags = ?
            WHERE id = ?
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalized(text.english()));
            statement.setString(2, normalized(text.chinese()));
            statement.setString(3, nullable(text.phonetic()));
            statement.setString(4, nullable(text.partOfSpeech()));
            statement.setString(5, nullable(text.exampleSentence()));
            statement.setString(6, nullable(text.note()));
            statement.setString(7, nullable(text.tags()));
            statement.setLong(8, id);
            return statement.executeUpdate() == 1;
        }
    }

    /**
     * Replaces the word's tags with {@code tags} and fills its phonetic with {@code phonetic} when it
     * has none, but only while its tags are still {@code expectedTags}: an edit made since they were
     * read wins, and nothing else of the word (its schedule above all) is written. Returns whether
     * the word was changed.
     */
    public boolean updateTagsIfUnchanged(long id, String expectedTags, String tags, String phonetic)
        throws SQLException {
        String sql = """
            UPDATE words
            SET tags = ?,
                phonetic = CASE WHEN TRIM(COALESCE(phonetic, '')) = '' AND ? <> '' THEN ? ELSE phonetic END
            WHERE id = ? AND COALESCE(tags, '') = ?
            """;
        String newPhonetic = phonetic == null ? "" : phonetic.strip();
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, nullable(tags));
            statement.setString(2, newPhonetic);
            statement.setString(3, newPhonetic);
            statement.setLong(4, id);
            statement.setString(5, expectedTags == null ? "" : expectedTags);
            return statement.executeUpdate() == 1;
        }
    }

    /**
     * The words of the decks tagged {@code tag} (one of their tags, ignoring case), suspended ones
     * too, in the decks' order and then by English word.
     */
    public List<WordCard> findTagged(Collection<Long> deckIds, String tag) throws SQLException {
        List<WordCard> tagged = new ArrayList<>();
        for (long deckId : new LinkedHashSet<>(deckIds)) {
            String sql = "SELECT * FROM words WHERE deck_id = ? AND lower(COALESCE(tags, '')) LIKE ? "
                + "ORDER BY lower(english), id";
            try (Connection connection = databaseManager.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, deckId);
                statement.setString(2, "%" + tag.toLowerCase(Locale.ROOT) + "%");
                try (ResultSet rs = statement.executeQuery()) {
                    // LIKE narrows the rows down; WordCard.hasTag decides, so "unchecked-later" is not "unchecked".
                    mapList(rs).stream().filter(word -> word.hasTag(tag)).forEach(tagged::add);
                }
            }
        }
        return tagged;
    }

    public void deleteById(long id) throws SQLException {
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM words WHERE id = ?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
    }

    /** Deletes the words, and with them their review logs, all or none; returns how many were deleted. */
    public int deleteByIds(Collection<Long> ids) throws SQLException {
        return updateByIds("DELETE FROM words WHERE id IN (%s)", null, ids);
    }

    /**
     * Suspends or unsuspends the words, all or none; their schedules stay as they are. Returns how
     * many words changed.
     */
    public int setSuspended(Collection<Long> ids, boolean suspended) throws SQLException {
        return updateByIds("UPDATE words SET archived = ? WHERE archived <> ? AND id IN (%s)", suspended ? 1 : 0, ids);
    }

    /**
     * Runs {@code sql} for the ids, {@value #IDS_PER_STATEMENT} at a time, in one transaction. The
     * {@code %s} in {@code sql} becomes the placeholders; {@code flag}, when not null, is bound to the
     * two {@code ?} before them.
     */
    private int updateByIds(String sql, Integer flag, Collection<Long> ids) throws SQLException {
        List<Long> distinct = List.copyOf(new LinkedHashSet<>(ids));
        if (distinct.isEmpty()) {
            return 0;
        }
        return databaseManager.inTransaction(() -> {
            int changed = 0;
            try (Connection connection = databaseManager.getConnection()) {
                for (int from = 0; from < distinct.size(); from += IDS_PER_STATEMENT) {
                    List<Long> chunk = distinct.subList(from, Math.min(distinct.size(), from + IDS_PER_STATEMENT));
                    String placeholders = String.join(", ", Collections.nCopies(chunk.size(), "?"));
                    try (PreparedStatement statement = connection.prepareStatement(sql.formatted(placeholders))) {
                        int index = 1;
                        if (flag != null) {
                            statement.setInt(index++, flag);
                            statement.setInt(index++, flag);
                        }
                        for (long id : chunk) {
                            statement.setLong(index++, id);
                        }
                        changed += statement.executeUpdate();
                    }
                }
            }
            return changed;
        });
    }

    public Optional<WordCard> findById(long id) throws SQLException {
        String sql = "SELECT * FROM words WHERE id = ?";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(map(rs));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The deck's word with this English text, ignoring case, suspended or not: the unique index on
     * (deck_id, english) covers suspended words too, so a duplicate check must find them.
     */
    public Optional<WordCard> findByEnglish(long deckId, String english) throws SQLException {
        String sql = "SELECT * FROM words WHERE deck_id = ? AND english = ? COLLATE NOCASE";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deckId);
            statement.setString(2, english.trim());
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(map(rs));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The words with this English text, ignoring case, in the active decks other than {@code deckId},
     * oldest deck first: what adding the word to {@code deckId} would duplicate elsewhere.
     */
    public List<WordCard> findInOtherDecks(String english, long deckId) throws SQLException {
        // Looked up deck by deck through the unique index on (deck_id, english COLLATE NOCASE).
        String sql = """
            SELECT w.* FROM decks d
            JOIN words w ON w.deck_id = d.id AND w.english = ? COLLATE NOCASE
            WHERE d.archived = 0 AND d.id <> ?
            ORDER BY d.id, w.id
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, english.trim());
            statement.setLong(2, deckId);
            try (ResultSet rs = statement.executeQuery()) {
                return mapList(rs);
            }
        }
    }

    /**
     * For each of {@code englishKeys} (lower case) that an active deck other than {@code deckId} has,
     * that word in the oldest such deck, by its lower-case English: what importing the words into
     * {@code deckId} (0 for a deck that does not exist yet) would duplicate elsewhere. Asked
     * {@value #IDS_PER_STATEMENT} words at a time through the unique index on (deck_id, english).
     */
    public Map<String, WordCard> findFirstInOtherDecks(Collection<String> englishKeys, long deckId)
        throws SQLException {
        Map<String, WordCard> found = new HashMap<>();
        List<String> keys = List.copyOf(new LinkedHashSet<>(englishKeys));
        for (int from = 0; from < keys.size(); from += IDS_PER_STATEMENT) {
            List<String> chunk = keys.subList(from, Math.min(keys.size(), from + IDS_PER_STATEMENT));
            String sql = """
                SELECT w.* FROM decks d
                JOIN words w ON w.deck_id = d.id AND w.english COLLATE NOCASE IN (%s)
                WHERE d.archived = 0 AND d.id <> ?
                ORDER BY d.id, w.id
                """.formatted(String.join(", ", Collections.nCopies(chunk.size(), "?")));
            try (Connection connection = databaseManager.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                for (String key : chunk) {
                    statement.setString(index++, key);
                }
                statement.setLong(index, deckId);
                try (ResultSet rs = statement.executeQuery()) {
                    for (WordCard word : mapList(rs)) {
                        found.putIfAbsent(word.getEnglish().trim().toLowerCase(Locale.ROOT), word);
                    }
                }
            }
        }
        return found;
    }

    /** The ids of the active decks, in the deck selector's order (by name). */
    public List<Long> findActiveDeckIds() throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT id FROM decks WHERE archived = 0 ORDER BY lower(name), id");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        }
        return ids;
    }

    /** Whether the word exists, is not suspended and its deck is active: whether a review can show it. */
    public boolean isReviewable(long wordId) throws SQLException {
        String sql = """
            SELECT 1 FROM words w JOIN decks d ON d.id = w.deck_id
            WHERE w.id = ? AND w.archived = 0 AND d.archived = 0
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, wordId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * The deck's English words in lower case, for duplicate checks without one query per word.
     * Suspended words are included, because the unique index on (deck_id, english) covers them too.
     */
    public Set<String> findEnglishKeys(long deckId) throws SQLException {
        Set<String> keys = new HashSet<>();
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT english FROM words WHERE deck_id = ?")) {
            statement.setLong(1, deckId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    keys.add(rs.getString(1).trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return keys;
    }

    /** The deck's words in study: every word except the suspended ones. */
    public List<WordCard> findAll(long deckId) throws SQLException {
        String sql = "SELECT * FROM words WHERE deck_id = ? AND archived = 0 ORDER BY lower(english)";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deckId);
            try (ResultSet rs = statement.executeQuery()) {
                return mapList(rs);
            }
        }
    }

    /** Every word in the deck, suspended ones included: what backups and the CSV export write. */
    public List<WordCard> findAllIncludingSuspended(long deckId) throws SQLException {
        String sql = "SELECT * FROM words WHERE deck_id = ? ORDER BY lower(english), id";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deckId);
            try (ResultSet rs = statement.executeQuery()) {
                return mapList(rs);
            }
        }
    }

    /**
     * The Word List: the deck's words, suspended ones included, whose English, Chinese or tags
     * contain {@code query} (every word when it is blank), by English word.
     */
    public List<WordCard> search(long deckId, String query) throws SQLException {
        if (query == null || query.isBlank()) {
            return findAllIncludingSuspended(deckId);
        }
        String like = "%" + query.trim().toLowerCase() + "%";
        String sql = """
            SELECT * FROM words
            WHERE deck_id = ?
              AND (lower(english) LIKE ? OR lower(chinese) LIKE ? OR lower(COALESCE(tags, '')) LIKE ?)
            ORDER BY lower(english), id
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deckId);
            statement.setString(2, like);
            statement.setString(3, like);
            statement.setString(4, like);
            try (ResultSet rs = statement.executeQuery()) {
                return mapList(rs);
            }
        }
    }

    /**
     * The deck's review cards (state {@code REVIEW}) due today, the ones most likely forgotten first:
     * by FSRS retrievability at {@code now}, lowest first, which is the order of elapsed time over
     * stability, highest first. A card without a last review time ranks as if it were exactly due.
     * Learning, relearning and new cards are left to {@link #findLearningDueBy} and {@link #findNewCards}.
     *
     * @param dayEnd the end of the current study day, after {@code now}
     */
    public List<WordCard> findDueReviews(long deckId, LocalDateTime now, LocalDateTime dayEnd, int limit)
        throws SQLException {
        String sql = """
            SELECT * FROM words
            WHERE deck_id = ? AND archived = 0 AND card_state = 'REVIEW' AND next_review_at < ?
            ORDER BY COALESCE((julianday(?) - julianday(last_reviewed_at)) / MAX(stability, 0.01), 1.0) DESC,
                     next_review_at ASC, id ASC
            LIMIT ?
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deckId);
            statement.setString(2, DateTimeUtil.toDatabase(dayEnd));
            statement.setString(3, DateTimeUtil.toDatabase(now));
            statement.setInt(4, limit);
            try (ResultSet rs = statement.executeQuery()) {
                return mapList(rs);
            }
        }
    }

    /**
     * The deck's new cards (never reviewed) that are due today, in the order they were added: the
     * cards a session introduces, up to the new-cards-per-day limit.
     *
     * @param dayEnd the end of the current study day
     */
    public List<WordCard> findNewCards(long deckId, LocalDateTime dayEnd, int limit) throws SQLException {
        String sql = """
            SELECT * FROM words
            WHERE deck_id = ? AND archived = 0 AND card_state = 'NEW' AND next_review_at < ?
            ORDER BY added_at ASC, id ASC
            LIMIT ?
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deckId);
            statement.setString(2, DateTimeUtil.toDatabase(dayEnd));
            statement.setInt(3, limit);
            try (ResultSet rs = statement.executeQuery()) {
                return mapList(rs);
            }
        }
    }

    /**
     * The deck's due words (see {@link #countDue}) split by state, in one query: learning and
     * relearning cards whose step time has come, review cards due today and new cards. Words
     * without a card state, which exist only until the startup backfill derived it, are in none.
     */
    public DueCounts countDueByState(long deckId, LocalDateTime now, LocalDateTime dayEnd) throws SQLException {
        String sql = """
            SELECT COALESCE(SUM(CASE WHEN card_state IN ('LEARNING', 'RELEARNING') AND next_review_at <= ?
                                     THEN 1 ELSE 0 END), 0) AS learning,
                   COALESCE(SUM(CASE WHEN card_state = 'REVIEW' THEN 1 ELSE 0 END), 0) AS review,
                   COALESCE(SUM(CASE WHEN card_state = 'NEW' THEN 1 ELSE 0 END), 0) AS new_cards
            FROM words
            WHERE deck_id = ? AND archived = 0 AND next_review_at < ?
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, DateTimeUtil.toDatabase(now));
            statement.setLong(2, deckId);
            statement.setString(3, DateTimeUtil.toDatabase(dayEnd));
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next()
                    ? new DueCounts(rs.getInt("learning"), rs.getInt("review"), rs.getInt("new_cards"))
                    : new DueCounts(0, 0, 0);
            }
        }
    }

    /**
     * Due words by state, see {@link #countDueByState}.
     *
     * @param learning learning and relearning cards whose step time has come
     * @param review   review cards due today
     * @param newCards new cards due today, before the new-cards-per-day limit
     */
    public record DueCounts(int learning, int review, int newCards) {
    }

    /**
     * The deck's learning and relearning cards due by {@code until}, soonest first: with an
     * {@code until} after now, the cards a session may show a little early when nothing else is due.
     */
    public List<WordCard> findLearningDueBy(long deckId, LocalDateTime until, int limit) throws SQLException {
        String sql = """
            SELECT * FROM words
            WHERE deck_id = ? AND archived = 0 AND next_review_at <= ? AND card_state IN ('LEARNING', 'RELEARNING')
            ORDER BY next_review_at ASC, id ASC
            LIMIT ?
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deckId);
            statement.setString(2, DateTimeUtil.toDatabase(until));
            statement.setInt(3, limit);
            try (ResultSet rs = statement.executeQuery()) {
                return mapList(rs);
            }
        }
    }

    /** The deck's weak words (see {@link WordCard#isWeak()}), most fragile first. */
    public List<WordCard> findWeak(long deckId, int limit) throws SQLException {
        String sql = "SELECT * FROM words WHERE deck_id = ? AND archived = 0 AND " + WEAK + """
            ORDER BY CASE WHEN card_state = 'RELEARNING' THEN 0 ELSE 1 END, consecutive_correct ASC,
                     difficulty DESC, next_review_at ASC, id ASC
            LIMIT ?
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deckId);
            bindWeakThresholds(statement, 2);
            statement.setInt(5, limit);
            try (ResultSet rs = statement.executeQuery()) {
                return mapList(rs);
            }
        }
    }

    /**
     * Words stored without an FSRS card state: written before schema version 5, or since by an
     * older version of the app. Their state is estimated from the SM-2 schedule until it is derived
     * and saved.
     */
    public List<WordCard> findWithoutCardState() throws SQLException {
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT * FROM words WHERE card_state IS NULL ORDER BY id");
             ResultSet rs = statement.executeQuery()) {
            return mapList(rs);
        }
    }

    /** Every word of the deck, suspended ones included: the Word List's rows without a filter. */
    public int countAll(long deckId) throws SQLException {
        return count("SELECT COUNT(*) FROM words WHERE deck_id = ?", deckId);
    }

    /** The deck's suspended words. */
    public int countSuspended(long deckId) throws SQLException {
        return count("SELECT COUNT(*) FROM words WHERE deck_id = ? AND archived <> 0", deckId);
    }

    /** Every word row in every deck, suspended or not. */
    public int countAllInDatabase() throws SQLException {
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM words");
             ResultSet rs = statement.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * The deck's due words (see {@link WordCard#isDue}); {@link #countDueByState} splits them by state.
     *
     * @param dayEnd the end of the current study day, after {@code now}
     */
    public int countDue(long deckId, LocalDateTime now, LocalDateTime dayEnd) throws SQLException {
        String sql = "SELECT COUNT(*) FROM words WHERE deck_id = ? AND archived = 0 AND " + DUE;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deckId);
            statement.setString(2, DateTimeUtil.toDatabase(dayEnd));
            statement.setString(3, DateTimeUtil.toDatabase(now));
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** The deck's mastered words (see {@link WordCard#isMastered()}). */
    public int countMastered(long deckId) throws SQLException {
        String sql = """
            SELECT COUNT(*) FROM words
            WHERE deck_id = ? AND archived = 0 AND card_state = 'REVIEW' AND stability >= ?
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deckId);
            statement.setDouble(2, WordCard.MASTERED_STABILITY_DAYS);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** The deck's active words that were never reviewed (state {@code NEW}), due or not. */
    public int countNew(long deckId) throws SQLException {
        return count("SELECT COUNT(*) FROM words WHERE deck_id = ? AND archived = 0 AND card_state = 'NEW'", deckId);
    }

    /**
     * Active review cards (state {@code REVIEW}) of every deck that are due at {@code from} or later,
     * such as the cards an exam date may bring forward.
     */
    public List<WordCard> findReviewCardsDueFrom(LocalDateTime from) throws SQLException {
        String sql = """
            SELECT * FROM words
            WHERE archived = 0 AND card_state = 'REVIEW' AND next_review_at >= ?
            ORDER BY deck_id, id
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, DateTimeUtil.toDatabase(from));
            try (ResultSet rs = statement.executeQuery()) {
                return mapList(rs);
            }
        }
    }

    /**
     * How many of the deck's learning, relearning and review cards are due on each study day from
     * {@code today} until {@code until}, in one query grouped by study day: a study day starts at
     * {@code rolloverHour}, and cards due before today (overdue) count for today. Days without due
     * cards are missing; new cards are not counted.
     *
     * @param until the start of the study day after the last one counted
     */
    public Map<LocalDate, Integer> countDueByStudyDay(long deckId, LocalDate today, int rolloverHour,
                                                     LocalDateTime until) throws SQLException {
        String sql = """
            SELECT MAX(date(next_review_at, ?), ?) AS study_day, COUNT(*) AS due
            FROM words
            WHERE deck_id = ? AND archived = 0 AND card_state IN ('LEARNING', 'RELEARNING', 'REVIEW')
              AND next_review_at < ?
            GROUP BY study_day
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "-" + rolloverHour + " hours");
            statement.setString(2, DateTimeUtil.toDatabaseDate(today));
            statement.setLong(3, deckId);
            statement.setString(4, DateTimeUtil.toDatabase(until));
            try (ResultSet rs = statement.executeQuery()) {
                Map<LocalDate, Integer> counts = new HashMap<>();
                while (rs.next()) {
                    counts.put(DateTimeUtil.dateFromDatabase(rs.getString("study_day")), rs.getInt("due"));
                }
                return counts;
            }
        }
    }

    /**
     * Word counts (suspended words included) and due-word counts (suspended words never due) of every
     * deck that has words, by deck id, in one query.
     *
     * @param dayEnd the end of the current study day, see {@link #countDue}
     */
    public Map<Long, DeckWordCounts> countByDeck(LocalDateTime now, LocalDateTime dayEnd) throws SQLException {
        String sql = "SELECT deck_id, COUNT(*) AS total, COALESCE(SUM(CASE WHEN archived = 0 AND " + DUE + """
                THEN 1 ELSE 0 END), 0) AS due,
                COALESCE(SUM(CASE WHEN archived = 0 AND card_state = 'NEW' AND next_review_at < ? THEN 1 ELSE 0 END), 0)
                    AS due_new
            FROM words
            GROUP BY deck_id
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, DateTimeUtil.toDatabase(dayEnd));
            statement.setString(2, DateTimeUtil.toDatabase(now));
            statement.setString(3, DateTimeUtil.toDatabase(dayEnd));
            try (ResultSet rs = statement.executeQuery()) {
                Map<Long, DeckWordCounts> counts = new HashMap<>();
                while (rs.next()) {
                    counts.put(rs.getLong("deck_id"),
                        new DeckWordCounts(rs.getInt("total"), rs.getInt("due"), rs.getInt("due_new")));
                }
                return counts;
            }
        }
    }

    /**
     * What {@link #countAll} and {@link #countDue} return for one deck, and how many of the due
     * words are new ({@link DueCounts#newCards()}).
     */
    public record DeckWordCounts(int total, int due, int dueNew) {
    }

    private int count(String sql, long deckId) throws SQLException {
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deckId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static void bindWeakThresholds(PreparedStatement statement, int firstIndex) throws SQLException {
        statement.setInt(firstIndex, WordCard.RECENT_REVIEWS);
        statement.setDouble(firstIndex + 1, WordCard.WEAK_DIFFICULTY);
        statement.setDouble(firstIndex + 2, WordCard.MASTERED_STABILITY_DAYS);
    }

    private void bindWord(PreparedStatement statement, WordCard word) throws SQLException {
        statement.setLong(1, word.getDeckId());
        statement.setString(2, normalized(word.getEnglish()));
        statement.setString(3, normalized(word.getChinese()));
        statement.setString(4, nullable(word.getPhonetic()));
        statement.setString(5, nullable(word.getPartOfSpeech()));
        statement.setString(6, nullable(word.getExampleSentence()));
        statement.setString(7, nullable(word.getNote()));
        statement.setString(8, nullable(word.getTags()));
        statement.setString(9, DateTimeUtil.toDatabase(word.getAddedAt()));
        statement.setString(10, DateTimeUtil.toDatabase(word.getLastReviewedAt()));
        statement.setString(11, DateTimeUtil.toDatabase(word.getNextReviewAt()));
        statement.setDouble(12, word.getEasinessFactor());
        statement.setInt(13, word.getIntervalDays());
        statement.setInt(14, word.getRepetitions());
        statement.setInt(15, word.getConsecutiveCorrect());
        statement.setInt(16, word.getLapses());
        statement.setInt(17, word.isSuspended() ? 1 : 0);
        statement.setString(18, word.getState().name());
        statement.setDouble(19, word.getStability());
        statement.setDouble(20, word.getDifficulty());
        statement.setInt(21, word.getLearningStep());
    }

    private List<WordCard> mapList(ResultSet rs) throws SQLException {
        List<WordCard> words = new ArrayList<>();
        while (rs.next()) {
            words.add(map(rs));
        }
        return words;
    }

    private WordCard map(ResultSet rs) throws SQLException {
        WordCard word = new WordCard();
        word.setId(rs.getLong("id"));
        word.setDeckId(rs.getLong("deck_id"));
        word.setEnglish(rs.getString("english"));
        word.setChinese(rs.getString("chinese"));
        word.setPhonetic(rs.getString("phonetic"));
        word.setPartOfSpeech(rs.getString("part_of_speech"));
        word.setExampleSentence(rs.getString("example_sentence"));
        word.setNote(rs.getString("note"));
        word.setTags(rs.getString("tags"));
        word.setAddedAt(DateTimeUtil.fromDatabase(rs.getString("added_at")));
        word.setLastReviewedAt(DateTimeUtil.fromDatabase(rs.getString("last_reviewed_at")));
        word.setNextReviewAt(DateTimeUtil.fromDatabase(rs.getString("next_review_at")));
        word.setEasinessFactor(rs.getDouble("easiness_factor"));
        word.setIntervalDays(rs.getInt("interval_days"));
        word.setRepetitions(rs.getInt("repetitions"));
        word.setConsecutiveCorrect(rs.getInt("consecutive_correct"));
        word.setLapses(rs.getInt("lapses"));
        word.setSuspended(rs.getInt("archived") != 0);
        word.setStability(rs.getDouble("stability"));
        word.setDifficulty(rs.getDouble("difficulty"));
        word.setLearningStep(rs.getInt("learning_step"));
        CardState state = cardState(rs.getString("card_state"));
        if (state == null) {
            word.estimateStateFromLegacySchedule();
        } else {
            word.setState(state);
        }
        return word;
    }

    /** The stored state, or null when there is none yet (or one this version does not know). */
    private static CardState cardState(String value) {
        if (value == null) {
            return null;
        }
        try {
            return CardState.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private String nullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
