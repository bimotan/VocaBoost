package com.vocabtrainer.service;

import com.vocabtrainer.domain.WordCard;

public interface AiService {
    boolean isAvailable();

    String explain(WordCard word);
}