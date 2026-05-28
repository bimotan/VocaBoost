package com.vocabtrainer.service;

import com.vocabtrainer.domain.WordCard;

public class MockAiService implements AiService {
    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String explain(WordCard word) {
        return "Mock AI：" + word.getEnglish() + " 的核心释义是“" + word.getChinese()
            + "”。建议结合例句和主动回忆复习。";
    }
}