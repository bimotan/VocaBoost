package com.vocabtrainer.domain;

public enum ReviewMode {
    EN_TO_ZH("英译中", "Enter Chinese meaning"),
    ZH_TO_EN("中译英", "Enter English word"),
    MIXED("混合模式", "Answer Chinese or English based on the current prompt"),
    WEAK_WORDS("弱词模式", "Review weak words first");

    private final String label;
    private final String prompt;

    ReviewMode(String label, String prompt) {
        this.label = label;
        this.prompt = prompt;
    }

    public String getLabel() {
        return label;
    }

    public String getPrompt() {
        return prompt;
    }
}
