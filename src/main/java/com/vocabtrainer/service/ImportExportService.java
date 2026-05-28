package com.vocabtrainer.service;

import com.vocabtrainer.domain.ValidatedWord;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.util.DateTimeUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

public class ImportExportService {
    private static final String GRE_STARTER_RESOURCE = "/data/gre_starter_sample.csv";
    private static final int MAX_GRE_IMPORT_WORDS = 2000;

    private final WordRepository wordRepository;
    private final WordValidationService validationService;

    public ImportExportService(WordRepository wordRepository) {
        this(wordRepository, new WordValidationService());
    }

    public ImportExportService(WordRepository wordRepository, WordValidationService validationService) {
        this.wordRepository = wordRepository;
        this.validationService = validationService;
    }

    public ImportResult importLegacyTxt(Path path, long deckId) {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return importLegacyTxt(reader, deckId);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read import file: " + path, e);
        }
    }

    public ImportResult importGreCsv(Path path, long deckId) {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return importGreCsv(reader, deckId);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read GRE CSV file: " + path, e);
        }
    }

    public ImportResult importBundledGreStarter(long deckId) {
        var stream = ImportExportService.class.getResourceAsStream(GRE_STARTER_RESOURCE);
        if (stream == null) {
            throw new IllegalStateException("Bundled GRE starter deck is missing: " + GRE_STARTER_RESOURCE);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return importGreCsv(reader, deckId);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read bundled GRE starter deck", e);
        }
    }

    private ImportResult importLegacyTxt(BufferedReader reader, long deckId) throws IOException {
        int imported = 0;
        int skipped = 0;
        List<String> messages = new ArrayList<>();
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
                    messages.add("Line " + lineNumber + " skipped: duplicate word " + card.getEnglish());
                    continue;
                }
                wordRepository.insert(card);
                imported++;
            } catch (IllegalArgumentException | SQLException e) {
                skipped++;
                messages.add("Line " + lineNumber + " skipped: " + e.getMessage());
            }
        }
        return new ImportResult(imported, skipped, messages);
    }

    private ImportResult importGreCsv(BufferedReader reader, long deckId) throws IOException {
        int imported = 0;
        int skipped = 0;
        List<String> messages = new ArrayList<>();
        String line;
        int lineNumber = 0;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            List<String> fields = parseCsvLine(line);
            if (lineNumber == 1 && !fields.isEmpty() && "english".equalsIgnoreCase(fields.get(0).trim())) {
                continue;
            }
            try {
                if (imported >= MAX_GRE_IMPORT_WORDS) {
                    messages.add("GRE import stopped at " + MAX_GRE_IMPORT_WORDS + " imported words.");
                    break;
                }
                if (fields.size() < 2) {
                    throw new IllegalArgumentException("CSV row must contain english and chinese");
                }
                String pos = fields.size() > 2 ? fields.get(2) : "";
                String example = fields.size() > 3 ? fields.get(3) : "";
                String tags = fields.size() > 4 ? fields.get(4) : "";
                ValidatedWord validated = validationService.validate(fields.get(0), fields.get(1), "", pos, example, "", tags);
                if (wordRepository.findByEnglish(deckId, validated.english()).isPresent()) {
                    skipped++;
                    messages.add("Line " + lineNumber + " skipped: duplicate word " + validated.english());
                    continue;
                }
                WordCard word = WordCard.createNew(deckId, validated.english(), validated.chinese());
                word.setPartOfSpeech(validated.partOfSpeech());
                word.setExampleSentence(validated.exampleSentence());
                word.setTags(validated.tags());
                wordRepository.insert(word);
                imported++;
            } catch (IllegalArgumentException | SQLException e) {
                skipped++;
                messages.add("Line " + lineNumber + " skipped: " + e.getMessage());
            }
        }
        return new ImportResult(imported, skipped, messages);
    }

    private WordCard parseLegacyLine(String line, long deckId) {
        String[] parts = line.split(";", -1);
        if (parts.length != 7) {
            throw new IllegalArgumentException("field count is not 7");
        }
        ValidatedWord validated = validationService.validate(parts[0], parts[1]);
        try {
            LocalDateTime addedAt = LocalDateTime.parse(parts[2].trim(), DateTimeUtil.LEGACY_FORMATTER);
            LocalDateTime lastReviewedAt = LocalDateTime.parse(parts[3].trim(), DateTimeUtil.LEGACY_FORMATTER);
            double easiness = Double.parseDouble(parts[4].trim());
            int intervalDays = Integer.parseInt(parts[5].trim());
            int consecutiveCorrect = Integer.parseInt(parts[6].trim());

            WordCard card = WordCard.createNew(deckId, validated.english(), validated.chinese());
            card.setAddedAt(addedAt);
            card.setLastReviewedAt(lastReviewedAt);
            card.setEasinessFactor(Math.max(1.3, easiness));
            card.setIntervalDays(Math.max(0, intervalDays));
            card.setConsecutiveCorrect(Math.max(0, consecutiveCorrect));
            card.setRepetitions(Math.max(0, consecutiveCorrect));
            card.setNextReviewAt(intervalDays <= 0 ? LocalDateTime.now() : lastReviewedAt.plusDays(intervalDays));
            return card;
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("date format is invalid");
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("review parameters must be numeric");
        }
    }

    private List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }
}
