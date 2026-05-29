package com.vocabtrainer.service;

import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.ValidatedWord;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.util.DateTimeUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BackupService {
    private static final Pattern OBJECT_PATTERN = Pattern.compile("\\{([^{}]+)}", Pattern.DOTALL);
    private static final Pattern FIELD_PATTERN_TEMPLATE = Pattern.compile("\"%s\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"");

    private final WordRepository wordRepository;
    private final ReviewLogRepository reviewLogRepository;
    private final DatabaseManager databaseManager;
    private final WordValidationService validationService;

    public BackupService(WordRepository wordRepository, ReviewLogRepository reviewLogRepository,
                         DatabaseManager databaseManager, WordValidationService validationService) {
        this.wordRepository = wordRepository;
        this.reviewLogRepository = reviewLogRepository;
        this.databaseManager = databaseManager;
        this.validationService = validationService;
    }

    public Path exportWordsCsv(long deckId, Path outputPath) {
        try {
            ensureParent(outputPath);
            List<String> rows = new ArrayList<>();
            rows.add("english,chinese,phonetic,pos,example,note,tags");
            for (WordCard word : wordRepository.findAll(deckId)) {
                rows.add(String.join(",",
                    csv(word.getEnglish()),
                    csv(word.getChinese()),
                    csv(word.getPhonetic()),
                    csv(word.getPartOfSpeech()),
                    csv(word.getExampleSentence()),
                    csv(word.getNote()),
                    csv(word.getTags())
                ));
            }
            Files.write(outputPath, rows, StandardCharsets.UTF_8);
            return outputPath;
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("无法导出单词 CSV", e);
        }
    }

    public Path exportReviewLogsCsv(long deckId, Path outputPath) {
        String sql = """
            SELECT w.english, l.reviewed_at, l.user_answer, l.correct_answer, l.similarity, l.rating, l.elapsed_millis
            FROM review_logs l
            JOIN words w ON w.id = l.word_id
            WHERE w.deck_id = ?
            ORDER BY l.reviewed_at, l.id
            """;
        try {
            ensureParent(outputPath);
            List<String> rows = new ArrayList<>();
            rows.add("english,reviewed_at,user_answer,correct_answer,similarity,rating,elapsed_millis");
            try (Connection connection = databaseManager.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, deckId);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        rows.add(String.join(",",
                            csv(rs.getString("english")),
                            csv(rs.getString("reviewed_at")),
                            csv(rs.getString("user_answer")),
                            csv(rs.getString("correct_answer")),
                            csv(String.valueOf(rs.getDouble("similarity"))),
                            csv(rs.getString("rating")),
                            csv(String.valueOf(rs.getLong("elapsed_millis")))
                        ));
                    }
                }
            }
            Files.write(outputPath, rows, StandardCharsets.UTF_8);
            return outputPath;
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("无法导出复习记录 CSV", e);
        }
    }

    public Path exportJsonBackup(long deckId, Path outputPath) {
        try {
            ensureParent(outputPath);
            Files.writeString(outputPath, buildJsonBackup(deckId), StandardCharsets.UTF_8);
            return outputPath;
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("无法导出 JSON 备份", e);
        }
    }

    public ImportResult importJsonBackup(Path inputPath, long deckId) {
        try {
            String payload = Files.readString(inputPath, StandardCharsets.UTF_8);
            ImportResult wordResult = importWordsFromJson(payload, deckId);
            int logCount = importReviewLogsFromJson(payload, deckId);
            List<String> messages = new ArrayList<>(wordResult.messages());
            messages.add("Review logs imported: " + logCount);
            return new ImportResult(wordResult.importedCount(), wordResult.skippedCount(), messages);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 JSON 备份", e);
        }
    }

    String buildJsonBackup(long deckId) throws SQLException {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n  \"version\": 1,\n  \"words\": [\n");
        List<WordCard> words = wordRepository.findAll(deckId);
        for (int i = 0; i < words.size(); i++) {
            WordCard word = words.get(i);
            builder.append("    {")
                .append("\"english\":\"").append(json(word.getEnglish())).append("\",")
                .append("\"chinese\":\"").append(json(word.getChinese())).append("\",")
                .append("\"phonetic\":\"").append(json(word.getPhonetic())).append("\",")
                .append("\"partOfSpeech\":\"").append(json(word.getPartOfSpeech())).append("\",")
                .append("\"exampleSentence\":\"").append(json(word.getExampleSentence())).append("\",")
                .append("\"note\":\"").append(json(word.getNote())).append("\",")
                .append("\"tags\":\"").append(json(word.getTags())).append("\"")
                .append("}");
            builder.append(i == words.size() - 1 ? "\n" : ",\n");
        }
        builder.append("  ],\n  \"reviewLogs\": [\n");
        appendReviewLogsJson(deckId, builder);
        builder.append("  ]\n}\n");
        return builder.toString();
    }

    private ImportResult importWordsFromJson(String payload, long deckId) {
        int imported = 0;
        int skipped = 0;
        List<String> messages = new ArrayList<>();
        String wordsSection = section(payload, "words");
        Matcher matcher = OBJECT_PATTERN.matcher(wordsSection);
        while (matcher.find()) {
            String object = matcher.group(1);
            try {
                ValidatedWord validated = validationService.validate(
                    field(object, "english"),
                    field(object, "chinese"),
                    field(object, "phonetic"),
                    field(object, "partOfSpeech"),
                    field(object, "exampleSentence"),
                    field(object, "note"),
                    field(object, "tags")
                );
                if (wordRepository.findByEnglish(deckId, validated.english()).isPresent()) {
                    skipped++;
                    messages.add("Skipped duplicate word: " + validated.english());
                    continue;
                }
                WordCard word = WordCard.createNew(deckId, validated.english(), validated.chinese());
                word.setPhonetic(validated.phonetic());
                word.setPartOfSpeech(validated.partOfSpeech());
                word.setExampleSentence(validated.exampleSentence());
                word.setNote(validated.note());
                word.setTags(validated.tags());
                wordRepository.save(word);
                imported++;
            } catch (IllegalArgumentException | SQLException e) {
                skipped++;
                messages.add("Skipped word: " + e.getMessage());
            }
        }
        return new ImportResult(imported, skipped, messages);
    }

    private int importReviewLogsFromJson(String payload, long deckId) {
        int imported = 0;
        String logsSection = section(payload, "reviewLogs");
        Matcher matcher = OBJECT_PATTERN.matcher(logsSection);
        while (matcher.find()) {
            String object = matcher.group(1);
            try {
                String english = field(object, "wordEnglish");
                WordCard word = wordRepository.findByEnglish(deckId, english).orElse(null);
                if (word == null) {
                    continue;
                }
                reviewLogRepository.insert(new ReviewLog(
                    0,
                    word.getId(),
                    DateTimeUtil.fromDatabase(field(object, "reviewedAt")),
                    field(object, "userAnswer"),
                    field(object, "correctAnswer"),
                    Double.parseDouble(field(object, "similarity")),
                    ReviewRating.valueOf(field(object, "rating")),
                    Long.parseLong(field(object, "elapsedMillis"))
                ));
                imported++;
            } catch (IllegalArgumentException | SQLException ignored) {
                // A backup import should restore as much as possible.
            }
        }
        return imported;
    }

    private void appendReviewLogsJson(long deckId, StringBuilder builder) throws SQLException {
        String sql = """
            SELECT w.english, l.reviewed_at, l.user_answer, l.correct_answer, l.similarity, l.rating, l.elapsed_millis
            FROM review_logs l
            JOIN words w ON w.id = l.word_id
            WHERE w.deck_id = ?
            ORDER BY l.reviewed_at, l.id
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deckId);
            try (ResultSet rs = statement.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) {
                        builder.append(",\n");
                    }
                    first = false;
                    builder.append("    {")
                        .append("\"wordEnglish\":\"").append(json(rs.getString("english"))).append("\",")
                        .append("\"reviewedAt\":\"").append(json(rs.getString("reviewed_at"))).append("\",")
                        .append("\"userAnswer\":\"").append(json(rs.getString("user_answer"))).append("\",")
                        .append("\"correctAnswer\":\"").append(json(rs.getString("correct_answer"))).append("\",")
                        .append("\"similarity\":\"").append(rs.getDouble("similarity")).append("\",")
                        .append("\"rating\":\"").append(json(rs.getString("rating"))).append("\",")
                        .append("\"elapsedMillis\":\"").append(rs.getLong("elapsed_millis")).append("\"")
                        .append("}");
                }
                if (!first) {
                    builder.append("\n");
                }
            }
        }
    }

    private String section(String payload, String name) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(payload);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String field(String object, String fieldName) {
        Pattern pattern = Pattern.compile(FIELD_PATTERN_TEMPLATE.pattern().formatted(Pattern.quote(fieldName)), Pattern.DOTALL);
        Matcher matcher = pattern.matcher(object);
        return matcher.find() ? unescapeJson(matcher.group(1)) : "";
    }

    private void ensureParent(Path outputPath) throws IOException {
        Path parent = outputPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private String csv(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private String json(String value) {
        String safe = value == null ? "" : value;
        return safe.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n");
    }

    private String unescapeJson(String value) {
        StringBuilder builder = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                builder.append(switch (c) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case '"' -> '"';
                    case '\\' -> '\\';
                    default -> c;
                });
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }
}
