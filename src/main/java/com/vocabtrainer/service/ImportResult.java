package com.vocabtrainer.service;

import java.util.List;

public record ImportResult(int importedCount, int skippedCount, List<String> messages) {
    public String toSummary() {
        StringBuilder builder = new StringBuilder();
        builder.append("导入成功 ").append(importedCount).append(" 条，跳过 ").append(skippedCount).append(" 条。");
        if (!messages.isEmpty()) {
            builder.append(System.lineSeparator()).append(String.join(System.lineSeparator(), messages));
        }
        return builder.toString();
    }
}