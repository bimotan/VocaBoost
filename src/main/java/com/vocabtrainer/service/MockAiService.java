package com.vocabtrainer.service;

import com.vocabtrainer.domain.WordCard;

public class MockAiService implements AiService {
    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String explain(WordCard word) {
        String example = word.getExampleSentence() == null || word.getExampleSentence().isBlank()
            ? "Try to make your own sentence with " + word.getEnglish() + "."
            : word.getExampleSentence();
        return "Mock AI：" + word.getEnglish() + " 的核心释义是“" + word.getChinese()
            + "”。记忆提示：把英文发音、词根或熟悉场景与中文释义绑定。"
            + System.lineSeparator() + "Example: " + example;
    }
}
