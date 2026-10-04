package com.vocabtrainer.repository;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.util.DateTimeUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.vocabtrainer.util.Messages.tr;

public class DeckRepository {
    /** The name of the deck a new database started with before the app was translated. */
    public static final String DEFAULT_DECK_NAME = "默认词库";

    private final DatabaseManager databaseManager;
    private final Clock clock;

    public DeckRepository(DatabaseManager databaseManager) {
        this(databaseManager, Clock.systemDefaultZone());
    }

    /** {@code clock} dates the decks this creates. */
    public DeckRepository(DatabaseManager databaseManager, Clock clock) {
        this.databaseManager = databaseManager;
        this.clock = clock;
    }

    /** {@link #ensureDefaultDeck(String)} with the name {@link #DEFAULT_DECK_NAME}. */
    public Deck ensureDefaultDeck() throws SQLException {
        return ensureDefaultDeck(DEFAULT_DECK_NAME);
    }

    /**
     * Returns the active deck named {@code name}. If only archived decks have that name, the newest
     * of them is restored with its words instead of creating an empty one.
     */
    public Deck ensureDefaultDeck(String name) throws SQLException {
        Optional<Deck> existing = findAnyByName(name);
        if (existing.isEmpty()) {
            return create(name);
        }
        Deck deck = existing.get();
        return deck.isArchived() ? restore(deck.getId()) : deck;
    }

    public Deck create(String name) throws SQLException {
        LocalDateTime now = LocalDateTime.now(clock);
        String sql = "INSERT INTO decks(name, created_at, archived) VALUES(?, ?, 0)";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, name.trim());
            statement.setString(2, DateTimeUtil.toDatabase(now));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return new Deck(keys.getLong(1), name.trim(), now);
                }
            }
        }
        throw new SQLException(tr("deck.error.createFailed"));
    }

    /**
     * The active deck named {@code name}, ignoring upper and lower case as the unique index on active
     * names does ({@code COLLATE NOCASE}: the letters A to Z), so "gre" finds "GRE".
     */
    public Optional<Deck> findByName(String name) throws SQLException {
        return findOneByName("""
            SELECT id, name, created_at, archived FROM decks WHERE name = ? COLLATE NOCASE AND archived = 0
            """, name);
    }

    /**
     * Like {@link #findByName(String)}, but also matches archived decks. Names are unique only among
     * active decks, so this prefers the active deck and otherwise returns the newest archived one,
     * one named exactly {@code name} before one that differs in case.
     */
    public Optional<Deck> findAnyByName(String name) throws SQLException {
        return findOneByName("""
            SELECT id, name, created_at, archived FROM decks WHERE name = ? COLLATE NOCASE
            ORDER BY archived, name = ? COLLATE BINARY DESC, id DESC LIMIT 1
            """, name);
    }

    /** Binds {@code name} to every parameter of {@code sql}. */
    private Optional<Deck> findOneByName(String sql, String name) throws SQLException {
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 1; index <= statement.getParameterMetaData().getParameterCount(); index++) {
                statement.setString(index, name);
            }
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(map(rs));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<Deck> findById(long id) throws SQLException {
        String sql = "SELECT id, name, created_at, archived FROM decks WHERE id = ?";
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

    public List<Deck> findAllActive() throws SQLException {
        String sql = "SELECT id, name, created_at, archived FROM decks WHERE archived = 0 ORDER BY lower(name), id";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            List<Deck> decks = new ArrayList<>();
            while (rs.next()) {
                decks.add(map(rs));
            }
            return decks;
        }
    }

    public List<Deck> findAllArchived() throws SQLException {
        String sql = "SELECT id, name, created_at, archived FROM decks WHERE archived = 1 ORDER BY lower(name), id";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            List<Deck> decks = new ArrayList<>();
            while (rs.next()) {
                decks.add(map(rs));
            }
            return decks;
        }
    }

    public List<Deck> findAllIncludingArchived() throws SQLException {
        String sql = "SELECT id, name, created_at, archived FROM decks ORDER BY archived, lower(name), id";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            List<Deck> decks = new ArrayList<>();
            while (rs.next()) {
                decks.add(map(rs));
            }
            return decks;
        }
    }

    public Deck rename(long id, String name) throws SQLException {
        String trimmed = name == null ? "" : name.trim();
        String sql = "UPDATE decks SET name = ? WHERE id = ? AND archived = 0";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, trimmed);
            statement.setLong(2, id);
            int updated = statement.executeUpdate();
            if (updated == 0) {
                throw new SQLException(tr("deck.error.notActive"));
            }
        }
        return findById(id).orElseThrow(() -> new SQLException(tr("deck.error.notFound")));
    }

    public void archive(long id) throws SQLException {
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement("UPDATE decks SET archived = 1 WHERE id = ?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
    }

    /**
     * Makes an archived deck active again. Names are unique among active decks only (an archived
     * deck's name can be reused), so restoring fails while an active deck has the same name, in any
     * upper and lower case.
     */
    public Deck restore(long id) throws SQLException {
        Deck deck = findById(id).orElseThrow(() -> new SQLException(tr("deck.error.notFound")));
        Optional<Deck> activeWithSameName = findByName(deck.getName());
        if (activeWithSameName.isPresent() && activeWithSameName.get().getId() != id) {
            throw new SQLException(restoreNameInUse(deck.getName(), activeWithSameName.get().getName()));
        }
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement("UPDATE decks SET archived = 0 WHERE id = ?")) {
            statement.setLong(1, id);
            int updated = statement.executeUpdate();
            if (updated == 0) {
                throw new SQLException(tr("deck.error.notFound"));
            }
        }
        return findById(id).orElseThrow(() -> new SQLException(tr("deck.error.notFound")));
    }

    /**
     * Why the archived deck {@code name} cannot be restored while the active deck {@code activeName}
     * has its name, which may differ in upper and lower case.
     */
    public static String restoreNameInUse(String name, String activeName) {
        return activeName.equals(name) ? tr("deck.error.restoreNameInUse", name)
            : tr("deck.error.restoreNameInUseCase", activeName, name);
    }

    private Deck map(ResultSet rs) throws SQLException {
        return new Deck(
            rs.getLong("id"),
            rs.getString("name"),
            DateTimeUtil.fromDatabase(rs.getString("created_at")),
            rs.getInt("archived") == 1
        );
    }
}
