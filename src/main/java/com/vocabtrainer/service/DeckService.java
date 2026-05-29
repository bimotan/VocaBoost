package com.vocabtrainer.service;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.repository.DeckRepository;

import java.sql.SQLException;
import java.util.List;

public class DeckService {
    private static final int MAX_DECK_NAME_LENGTH = 60;

    private final DeckRepository deckRepository;

    public DeckService(DeckRepository deckRepository) {
        this.deckRepository = deckRepository;
    }

    public Deck ensureDefaultDeck() {
        try {
            return deckRepository.ensureDefaultDeck();
        } catch (SQLException e) {
            throw new IllegalStateException("无法初始化默认词库", e);
        }
    }

    public List<Deck> activeDecks() {
        try {
            List<Deck> decks = deckRepository.findAllActive();
            if (decks.isEmpty()) {
                decks = List.of(deckRepository.ensureDefaultDeck());
            }
            return decks;
        } catch (SQLException e) {
            throw new IllegalStateException("无法读取词库列表", e);
        }
    }

    public Deck createDeck(String name) {
        String cleanName = validateName(name);
        try {
            return deckRepository.create(cleanName);
        } catch (SQLException e) {
            throw new IllegalArgumentException("创建失败：词库名可能已存在", e);
        }
    }

    public Deck renameDeck(long id, String name) {
        String cleanName = validateName(name);
        try {
            return deckRepository.rename(id, cleanName);
        } catch (SQLException e) {
            throw new IllegalArgumentException("重命名失败：词库名可能已存在", e);
        }
    }

    public Deck archiveDeck(long id) {
        try {
            List<Deck> decks = deckRepository.findAllActive();
            if (decks.size() <= 1) {
                throw new IllegalArgumentException("至少需要保留一个活动词库");
            }
            deckRepository.archive(id);
            List<Deck> remaining = deckRepository.findAllActive();
            return remaining.isEmpty() ? deckRepository.ensureDefaultDeck() : remaining.get(0);
        } catch (SQLException e) {
            throw new IllegalStateException("归档词库失败", e);
        }
    }

    private String validateName(String name) {
        String cleanName = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (cleanName.isBlank()) {
            throw new IllegalArgumentException("词库名称不能为空");
        }
        if (cleanName.length() > MAX_DECK_NAME_LENGTH) {
            throw new IllegalArgumentException("词库名称不能超过 " + MAX_DECK_NAME_LENGTH + " 个字符");
        }
        return cleanName;
    }
}
