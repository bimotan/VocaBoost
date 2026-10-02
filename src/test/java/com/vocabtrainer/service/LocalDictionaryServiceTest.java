package com.vocabtrainer.service;

import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.WordVerificationResult;
import com.vocabtrainer.repository.EcdictRepository;
import com.vocabtrainer.service.ecdict.EcdictFixtures;
import com.vocabtrainer.service.ecdict.EcdictImportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalDictionaryServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void withoutAnImportTheBundledStarterWordsAnswer() {
        try (EcdictRepository ecdict = new EcdictRepository(tempDir.resolve("ecdict.db"))) {
            LocalDictionaryService service = new LocalDictionaryService(ecdict);

            DictionaryEntry abate = service.lookup("ABATE").entries().get(0);
            assertEquals("减弱; 减少", abate.chinese());
            assertEquals("verb", abate.partOfSpeech());
            assertEquals(LocalDictionaryService.STARTER_SOURCE, abate.source());
            assertTrue(service.verify("abate").found());
            assertFalse(service.verify("notarealword").found());
            assertEquals("词条未找到：本地词库没有该词条。", service.lookup("notarealword").message());
            assertFalse(service.status().ecdictImported());
            assertEquals("ECDICT: not imported. Bundled GRE starter: 215 entries.", service.status().toDisplayText());
            assertFalse(Files.exists(tempDir.resolve("ecdict.db")), "looking up must not create the dictionary file");
        }
    }

    @Test
    void theImportedDictionaryAnswersBeforeTheStarterWordsAndItsStatusComesFromTheImport() throws Exception {
        Path csv = EcdictFixtures.write(tempDir.resolve("ecdict.csv"), false, List.of(
            EcdictFixtures.ABANDON, "lucid,ˈluːsɪd,,\"a. 清楚的, 透明的\",,,,,0,0,,,", "badrow,,,,,,,,0,0,,,"));
        try (EcdictRepository ecdict = new EcdictRepository(tempDir.resolve("ecdict.db"))) {
            new EcdictImportService(ecdict).importCsv(csv, progress -> { }, () -> false);
            LocalDictionaryService service = new LocalDictionaryService(ecdict);

            assertEquals("清楚的; 透明的", service.lookup("lucid").entries().get(0).chinese());
            assertEquals("Loaded from local dictionary.", service.lookup("lucid").message());
            assertEquals("Local dictionary", service.verify("Lucid").source());
            LocalDictionaryStatus status = service.status();
            assertTrue(status.ecdictImported());
            assertTrue(status.toDisplayText().startsWith("ECDICT: 2 entries from " + csv.toAbsolutePath() + ", imported "),
                status.toDisplayText());
            assertTrue(status.toDisplayText().endsWith(" (skipped rows: 1). Bundled GRE starter: 215 entries."),
                status.toDisplayText());
        }
    }

    @Test
    void theImportedDictionaryListsInflectionsAndGivesThePhoneticOfTheWordAsked() throws Exception {
        Path csv = EcdictFixtures.write(tempDir.resolve("ecdict.csv"), false, List.of(
            EcdictFixtures.ABANDON, EcdictFixtures.ABANDONED, "lucid,ˈluːsɪd,,\"a. 清楚的, 透明的\",,,,,0,0,,,"));
        try (EcdictRepository ecdict = new EcdictRepository(tempDir.resolve("ecdict.db"))) {
            LocalDictionaryService service = new LocalDictionaryService(ecdict);
            assertEquals(Set.of(), service.inflections("abandon"), "nothing is imported yet");
            new EcdictImportService(ecdict).importCsv(csv, progress -> { }, () -> false);

            assertEquals(Set.of("abandoned", "abandoning", "abandons"), service.inflections("Abandon"));
            assertEquals(Set.of(), service.inflections("abandoned"), "an inflected form's own row lists no forms of it");
            assertEquals(Set.of(), service.inflections("lucid"));
            assertEquals(Set.of(), service.inflections("notarealword"));

            assertEquals("ˈluːsɪd", service.verify("lucid").phonetic());
            assertEquals("ә'bændәn", service.verify("abandon").phonetic());
            WordVerificationResult form = service.verify("abandons");
            assertTrue(form.found());
            assertEquals("", form.phonetic(), "the base form's phonetic is not the form's");
            assertEquals("", service.verify("abate").phonetic(), "the starter words have no phonetic");
        }
    }

    @Test
    void creatingTheServiceAndLookingUpNeverReadTheCsv() throws Exception {
        Path csv = EcdictFixtures.write(tempDir.resolve("ecdict.csv"), false, List.of(EcdictFixtures.ABANDON));
        try (EcdictRepository ecdict = new EcdictRepository(tempDir.resolve("ecdict.db"))) {
            new EcdictImportService(ecdict).importCsv(csv, progress -> { }, () -> false);
        }
        // Earlier versions parsed the whole CSV into memory whenever this service was created (findings C5, D3).
        Files.writeString(csv, "this is not the dictionary any more");

        try (EcdictRepository ecdict = new EcdictRepository(tempDir.resolve("ecdict.db"))) {
            LocalDictionaryService service = new LocalDictionaryService(ecdict);

            assertEquals("放弃; 抛弃; 遗弃; 使屈从; 沉溺; 放纵; 放任; 无拘束; 狂热",
                service.lookup("abandon").entries().get(0).chinese());
            assertEquals(1, service.status().ecdict().rowCount());
        }
    }
}
