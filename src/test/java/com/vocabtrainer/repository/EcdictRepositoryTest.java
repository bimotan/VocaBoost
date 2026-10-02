package com.vocabtrainer.repository;

import com.vocabtrainer.domain.EcdictMetadata;
import com.vocabtrainer.domain.EcdictRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EcdictRepositoryTest {
    private static final int ENTRIES = 50_000;

    @TempDir
    Path tempDir;

    private EcdictRepository repository;

    @AfterEach
    void close() {
        if (repository != null) {
            repository.close();
        }
    }

    @Test
    void lookupsUseIndexesAndStayFastOnALargeDictionary() throws Exception {
        repository = new EcdictRepository(tempDir.resolve("ecdict.db"));
        fill(repository, ENTRIES);

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("ecdict.db"))) {
            List<String> find = plan(connection, EcdictRepository.FIND_SQL, "w123");
            assertEquals(List.of("SEARCH ecdict USING INDEX sqlite_autoindex_ecdict_1 (word=?)"), find);
            List<String> baseForms = plan(connection, EcdictRepository.FIND_BASE_FORMS_SQL, "w5ed", 5);
            assertTrue(baseForms.contains("SEARCH f USING PRIMARY KEY (form=?)"), baseForms.toString());
            assertTrue(baseForms.contains("SEARCH e USING INDEX sqlite_autoindex_ecdict_1 (word=?)"), baseForms.toString());
            assertTrue(baseForms.stream().noneMatch(line -> line.startsWith("SCAN")), baseForms.toString());
        }

        long started = System.nanoTime();
        for (int i = 0; i < 5_000; i++) {
            assertTrue(repository.find("W" + (i * 7 % ENTRIES)).isPresent());
            assertFalse(repository.find("missing" + i).isPresent());
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        // About 0.05 ms per lookup on a laptop; a full scan of 50,000 rows would take far longer.
        assertTrue(millis < 5_000, "10,000 lookups took " + millis + " ms");
        assertEquals("w5", repository.findBaseForms("W5ED").get(0).row().word());
        assertEquals("dp", repository.findBaseForms("w5ed").get(0).kinds());
    }

    @Test
    void anUnfinishedImportIsNeverVisibleAndOnlyOneRunsAtATime() throws Exception {
        repository = new EcdictRepository(tempDir.resolve("ecdict.db"));
        assertTrue(repository.metadata().isEmpty());
        assertTrue(repository.find("w1").isEmpty());

        try (EcdictRepository.EcdictImport unfinished = repository.beginImport()) {
            unfinished.addRow(row("w1", "测试"));
            assertEquals(1, unfinished.countRows());
            assertThrows(IllegalStateException.class, repository::beginImport);
            assertTrue(repository.find("w1").isEmpty());
            assertTrue(Files.exists(tempDir.resolve("ecdict.db.importing")));
        }

        assertFalse(Files.exists(tempDir.resolve("ecdict.db.importing")));
        assertFalse(Files.exists(tempDir.resolve("ecdict.db")));
        assertFalse(repository.isImporting());
        fill(repository, 10);
        assertEquals("意义3", repository.find("w3").orElseThrow().translation());
    }

    @Test
    void aSecondWindowNeitherDeletesNorJoinsTheImportAnotherOneIsBuilding() throws Exception {
        repository = new EcdictRepository(tempDir.resolve("ecdict.db"));
        try (EcdictRepository.EcdictImport running = repository.beginImport()) {
            running.addRow(row("w1", "测试"));

            try (EcdictRepository second = new EcdictRepository(tempDir.resolve("ecdict.db"))) {
                assertTrue(Files.exists(tempDir.resolve("ecdict.db.importing")), "the starting window deleted it");
                IllegalStateException error = assertThrows(IllegalStateException.class, second::beginImport);
                assertEquals("Another VocaBoost window is importing ECDICT. Try again when it has finished.",
                    error.getMessage());
                assertTrue(Files.exists(tempDir.resolve("ecdict.db.importing")));

                running.commit(new EcdictMetadata("/data/ecdict.csv", 1, 2, 1, 0, LocalDateTime.of(2026, 10, 2, 9, 0),
                    "test", 5));
                assertEquals("测试", second.find("w1").orElseThrow().translation());
                fill(second, 3);
                assertEquals("意义2", repository.find("w2").orElseThrow().translation());
            }
        }
    }

    @Test
    void anImportInAnotherProcessIsLeftAloneAndBlocksThisOne() throws Exception {
        Path database = tempDir.resolve("ecdict.db");
        Process other = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"), ImportInAnotherProcess.class.getName(), database.toString())
            .redirectErrorStream(true)
            .start();
        try (BufferedReader output = new BufferedReader(new InputStreamReader(other.getInputStream(),
            StandardCharsets.UTF_8))) {
            String line;
            do {
                line = output.readLine();
            } while (line != null && !line.equals(ImportInAnotherProcess.READY));
            assertEquals(ImportInAnotherProcess.READY, line);

            repository = new EcdictRepository(database);
            assertTrue(Files.exists(tempDir.resolve("ecdict.db.importing")), "the starting window deleted it");
            assertThrows(IllegalStateException.class, repository::beginImport);

            other.getOutputStream().close();
            assertTrue(other.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, other.exitValue());
        } finally {
            other.destroyForcibly();
        }
        assertEquals("测试", repository.find("w1").orElseThrow().translation());
        fill(repository, 3);
        assertEquals("意义2", repository.find("w2").orElseThrow().translation());
    }

    /** Begins an import, says {@link #READY}, and commits it when its input closes. */
    public static final class ImportInAnotherProcess {
        static final String READY = "import running";

        public static void main(String[] args) throws Exception {
            try (EcdictRepository repository = new EcdictRepository(Path.of(args[0]));
                 EcdictRepository.EcdictImport running = repository.beginImport()) {
                running.addRow(row("w1", "测试"));
                System.out.println(READY);
                System.out.flush();
                System.in.readAllBytes();
                running.commit(new EcdictMetadata("/data/ecdict.csv", 1, 2, 1, 0, LocalDateTime.of(2026, 10, 2, 9, 0),
                    "test", 5));
            }
        }
    }

    @Test
    void aFileWrittenByAnotherFormatVersionReadsAsNotImported() throws Exception {
        repository = new EcdictRepository(tempDir.resolve("ecdict.db"));
        fill(repository, 10);
        repository.close();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("ecdict.db"));
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = " + (EcdictRepository.FORMAT_VERSION + 1));
        }

        repository = new EcdictRepository(tempDir.resolve("ecdict.db"));

        assertTrue(repository.metadata().isEmpty());
        assertTrue(repository.find("w3").isEmpty());
    }

    private static void fill(EcdictRepository repository, int entries) throws Exception {
        try (EcdictRepository.EcdictImport target = repository.beginImport()) {
            for (int i = 0; i < entries; i++) {
                target.addRow(row("w" + i, "意义" + i));
                if (i % 5 == 0) {
                    target.addForm("w" + i + "ed", "w" + i, "d");
                    target.addForm("w" + i + "ed", "w" + i, "p");
                }
            }
            int rows = target.countRows();
            target.commit(new EcdictMetadata("/data/ecdict.csv", 1, 2, rows, 0, LocalDateTime.of(2026, 10, 2, 9, 0),
                "test", 5));
        }
    }

    private static EcdictRow row(String word, String translation) {
        return new EcdictRow(word, "", "", translation, "", null, null, "gre", null, 1, "", "");
    }

    private static List<String> plan(Connection connection, String sql, Object... parameters) throws Exception {
        List<String> lines = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("EXPLAIN QUERY PLAN " + sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    lines.add(rs.getString("detail"));
                }
            }
        }
        return lines;
    }
}
