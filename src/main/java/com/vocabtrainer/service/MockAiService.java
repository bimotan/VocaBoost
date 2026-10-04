package com.vocabtrainer.service;

import com.vocabtrainer.domain.WordCard;

import static com.vocabtrainer.util.Messages.tr;

/**
 * The explanation shown without an AI provider (or while offline mode is on, or when the provider
 * failed): the word's meaning, a generic memory tip and its example, in the app's language.
 */
public class MockAiService implements AiService {
    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String explain(WordCard word) {
        String example = word.getExampleSentence() == null || word.getExampleSentence().isBlank()
            ? tr("ai.mock.ownSentence", word.getEnglish())
            : word.getExampleSentence();
        return tr("ai.mock.meaning", word.getEnglish(), word.getChinese())
            + System.lineSeparator() + tr("ai.section.example", example);
    }
}
