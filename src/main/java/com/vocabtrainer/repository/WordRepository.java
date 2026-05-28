package com.vocabtrainer.repository;

import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.util.DateTimeUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class WordRepository {
    private final DatabaseManager databaseManager;

    public WordRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public WordCard save(WordCard word) throws SQLException {
        if (word.getId() == 0) {
            return insert(word);
        }
        update(word);
        return word;
    }

    public WordCard insert(WordCard word) throws SQLException {
        String sql = """
            INSERT INTO words(deck_id, english, chinese, phonetic, part_of_speech, example_sentence, note, tags,
                              added_at, last_reviewed_at, next_review_at, easiness_factor, interval_days,
                              repetitions, consecutive_correct, lapses, archived)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
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

    public void update(WordCard word) throws SQLException {
        String sql = """
            UPDATE words
            SET deck_id = ?, english = ?, chinese = ?, phonetic = ?, part_of_speech = ?, example_sentence = ?,
                note = ?, tags = ?, added_at = ?, last_reviewed_at = ?, next_review_at = ?, easiness_factor = ?,
                interval_days = ?, repetitions = ?, consecutive_correct = ?, lapses = ?, archived = ?
            WHERE id = ?
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindWord(statement, word);
            statement.setLong(18, word.getId());
            statement.executeUpdate();
        }
    }

    public void deleteById(long id) throws SQLException {
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM words WHERE id = ?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
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

    public Optional<WordCard> findByEnglish(long deckId, String english) throws SQLException {
        String sql = "SELECT * FROM words WHERE deck_id = ? AND english = ? COLLATE NOCASE AND archived = 0";
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

    public List<WordCard> search(long deckId, String query) throws SQLException {
        if (query == null || query.isBlank()) {
            return findAll(deckId);
        }
        String like = "%" + query.trim().toLowerCase() + "%";
        String sql = """
            SELECT * FROM words
            WHERE deck_id = ? AND archived = 0
              AND (lower(english) LIKE ? OR lower(chinese) LIKE ? OR lower(COALESCE(tags, '')) LIKE ?)
            ORDER BY lower(english)
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

    public List<WordCard> findDue(long deckId, LocalDateTime now, int limit) throws SQLException {
        String sql = """
            SELECT * FROM words
            WHERE deck_id = ? AND archived = 0 AND next_review_at <= ?
            ORDER BY next_review_at ASC, id ASC
            LIMIT ?
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deckId);
            statement.setString(2, DateTimeUtil.toDatabase(now));
            statement.setInt(3, limit);
            try (ResultSet rs = statement.executeQuery()) {
                return mapList(rs);
            }
        }
    }

    public int countAll(long deckId) throws SQLException {
        return count("SELECT COUNT(*) FROM words WHERE deck_id = ? AND archived = 0", deckId, null);
    }

    public int countDue(long deckId, LocalDateTime now) throws SQLException {
        return count("SELECT COUNT(*) FROM words WHERE deck_id = ? AND archived = 0 AND next_review_at <= ?", deckId, now);
    }

    public int countMastered(long deckId) throws SQLException {
        String sql = """
            SELECT COUNT(*) FROM words
            WHERE deck_id = ? AND archived = 0 AND consecutive_correct >= 3 AND interval_days >= 7 AND lapses = 0
            """;
        return count(sql, deckId, null);
    }

    private int count(String sql, long deckId, LocalDateTime now) throws SQLException {
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deckId);
            if (now != null) {
                statement.setString(2, DateTimeUtil.toDatabase(now));
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
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
        statement.setInt(17, word.isArchived() ? 1 : 0);
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
        word.setArchived(rs.getInt("archived") == 1);
        return word;
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private String nullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}