package com.vocabtrainer.service;

import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.util.DateTimeUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

public class ImportExportService {
    private final WordRepository wordRepository;

    public ImportExportService(WordRepository wordRepository) {
        this.wordRepository = wordRepository;
    }

    public ImportResult importLegacyTxt(Path path, long deckId) {
        int imported = 0;
        int skipped = 0;
        List<String> messages = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    WordCard card = parseLegacyLine(line, deckId);
                    if (wordRepository.findByEnglish(deckId, card.getEnglish()).isPresent()) {
                        skipped++;
                        messages.add("第 " + lineNumber + " 行跳过：单词已存在（" + card.getEnglish() + "）");
                        continue;
                    }
                    wordRepository.insert(card);
                    imported++;
                } catch (IllegalArgumentException | SQLException e) {
                    skipped++;
                    messages.add("第 " + lineNumber + " 行跳过：" + e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取导入文件失败: " + path, e);
        }
        return new ImportResult(imported, skipped, messages);
    }

    private WordCard parseLegacyLine(String line, long deckId) {
        String[] parts = line.split(";", -1);
        if (parts.length != 7) {
            throw new IllegalArgumentException("字段数量不是 7");
        }
        String english = parts[0].trim();
        String chinese = parts[1].trim();
        if (english.isEmpty() || chinese.isEmpty()) {
            throw new IllegalArgumentException("英文或中文为空");
        }
        try {
            LocalDateTime addedAt = LocalDateTime.parse(parts[2].trim(), DateTimeUtil.LEGACY_FORMATTER);
            LocalDateTime lastReviewedAt = LocalDateTime.parse(parts[3].trim(), DateTimeUtil.LEGACY_FORMATTER);
            double easiness = Double.parseDouble(parts[4].trim());
            int intervalDays = Integer.parseInt(parts[5].trim());
            int consecutiveCorrect = Integer.parseInt(parts[6].trim());

            WordCard card = WordCard.createNew(deckId, english, chinese);
            card.setAddedAt(addedAt);
            card.setLastReviewedAt(lastReviewedAt);
            card.setEasinessFactor(Math.max(1.3, easiness));
            card.setIntervalDays(Math.max(0, intervalDays));
            card.setConsecutiveCorrect(Math.max(0, consecutiveCorrect));
            card.setRepetitions(Math.max(0, consecutiveCorrect));
            card.setNextReviewAt(intervalDays <= 0 ? LocalDateTime.now() : lastReviewedAt.plusDays(intervalDays));
            return card;
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("日期格式错误");
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("复习参数不是数字");
        }
    }
}