package com.vocabtrainer.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalDictionaryServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsHeaderCsvAndVerifiesWord() throws Exception {
        Path csv = tempDir.resolve("ecdict.csv");
        Files.writeString(csv, """
            word,translation,phonetic,definition
            abate,减少,əˈbeɪt,to become weaker
            badrow,,,
            """);

        LocalDictionaryService service = new LocalDictionaryService(csv.toString());

        assertTrue(service.status().configuredPathLoaded());
        assertTrue(service.status().loadedCount() >= 1);
        assertTrue(service.status().skippedRows() >= 1);
        assertTrue(service.verify("abate").found());
        assertEquals("减少", service.lookup("ABATE").entries().get(0).chinese());
    }

    @Test
    void loadsCommonEcdictColumnOrderWithoutHeader() throws Exception {
        Path csv = tempDir.resolve("ecdict-no-header.csv");
        Files.writeString(csv, "lucid,ˈluːsɪd,definition,清晰的,adj\n");

        LocalDictionaryService service = new LocalDictionaryService(csv.toString());

        assertTrue(service.verify("lucid").found());
        assertEquals("清晰的", service.lookup("lucid").entries().get(0).chinese());
    }
}
