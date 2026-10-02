package com.vocabtrainer.service.ecdict;

import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.domain.EcdictMetadata;
import com.vocabtrainer.repository.EcdictRepository;
import com.vocabtrainer.service.LocalDictionaryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EcdictImportServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-02T08:30:00Z"), ZoneId.of("UTC"));

    @TempDir
    Path tempDir;

    private final List<EcdictRepository> repositories = new ArrayList<>();

    @AfterEach
    void closeRepositories() {
        repositories.forEach(EcdictRepository::close);
    }

    private EcdictRepository repository() {
        EcdictRepository repository = new EcdictRepository(tempDir.resolve("ecdict.db"));
        repositories.add(repository);
        return repository;
    }

    @Test
    void importsAnEcdictCsvWithByteOrderMarkQuotedFieldsAndLiteralLineBreaks() throws Exception {
        Path csv = EcdictFixtures.write(tempDir.resolve("ecdict.csv"), true, EcdictFixtures.REAL_ROWS);
        EcdictRepository repository = repository();
        EcdictImportService service = new EcdictImportService(repository, CLOCK);
        List<EcdictImportProgress> updates = new ArrayList<>();

        EcdictMetadata imported = service.importCsv(csv, updates::add, () -> false);

        assertEquals(6, imported.rowCount());
        assertEquals(0, imported.skippedRows());
        assertEquals(csv.toAbsolutePath().toString(), imported.sourcePath());
        assertEquals(Files.size(csv), imported.sourceSize());
        assertEquals(LocalDateTime.of(2026, 10, 2, 8, 30), imported.importedAt());
        assertEquals("Encoding: UTF-8 with BOM | Delimiter: comma | Columns: word, phonetic, definition, translation,"
            + " pos, collins, oxford, tag, bnc, frq, exchange (header row)", imported.format());
        assertEquals(imported, service.imported().orElseThrow());
        assertEquals(new EcdictImportProgress(6, Files.size(csv), Files.size(csv)), updates.get(updates.size() - 1));

        var abandon = repository.find("ABANDON").orElseThrow();
        assertEquals("abandon", abandon.word());
        assertEquals("ә'bændәn", abandon.phonetic());
        // Stored as ECDICT has it; lookups clean it.
        assertEquals("vt. 放弃, 抛弃, 遗弃, 使屈从, 沉溺, 放纵\\nn. 放任, 无拘束, 狂热", abandon.translation());
        assertEquals("gk cet4 cet6 ky toefl gre", abandon.tag());
        assertEquals(3, abandon.collins());
        assertEquals(1, abandon.oxford());
        assertEquals(2057, abandon.bnc());
        assertEquals(2182, abandon.frq());
        assertEquals("d:abandoned/p:abandoned/i:abandoning/3:abandons", abandon.exchange());
        var a = repository.find("A").orElseThrow();
        assertEquals("第一个字母 A; 一个; 第一的\\r\\nart. [计] 累加器, 加法器, 地址, 振幅, 模拟, 区域, 面积, 汇编, 组件, 异步",
            a.translation());
        assertEquals("zk gk", a.tag());
        assertEquals(5, a.frq());
        assertEquals("", a.exchange());
    }

    @Test
    void lookupsCleanTheTranslationAndFillPartOfSpeechNoteAndDefinition() throws Exception {
        EcdictRepository repository = imported(EcdictFixtures.REAL_ROWS);
        LocalDictionaryService local = new LocalDictionaryService(repository);

        DictionaryEntry abandon = local.lookup("abandon").entries().get(0);
        assertEquals("放弃; 抛弃; 遗弃; 使屈从; 沉溺; 放纵; 放任; 无拘束; 狂热", abandon.chinese());
        assertEquals("verb; noun", abandon.partOfSpeech());
        assertEquals(LocalDictionaryService.ECDICT_SOURCE, abandon.source());
        assertTrue(abandon.definition().startsWith("n. the trait of lacking restraint or control; reckless freedom"
            + " from inhibition or worry\nv. forsake, leave behind\n"), abandon.definition());

        DictionaryEntry hood = local.lookup("'hood").entries().get(0);
        assertEquals("[网络] 胡德；兜帽；引擎盖", hood.note());
        assertTrue(hood.chinese().contains("使(马,鹰等)戴头罩"), hood.chinese());
        // The bundled starter words still answer after ECDICT.
        assertEquals(LocalDictionaryService.STARTER_SOURCE, local.lookup("lucid").entries().get(0).source());
    }

    @Test
    void aMissingInflectionFindsItsBaseFormThroughTheExchangeField() throws Exception {
        LocalDictionaryService local = new LocalDictionaryService(imported(EcdictFixtures.REAL_ROWS));

        // "abandoned" has its own row; "abandons" and "abandoning" only appear in abandon's exchange field.
        assertEquals("abandoned", local.lookup("abandoned").entries().get(0).english());
        DictionaryLookupResult abandons = local.lookup("abandons");
        assertTrue(abandons.success());
        assertEquals("abandon", abandons.entries().get(0).english());
        assertEquals("放弃; 抛弃; 遗弃; 使屈从; 沉溺; 放纵; 放任; 无拘束; 狂热", abandons.entries().get(0).chinese());
        assertEquals("Not in the local dictionary as written: abandons is the third-person singular of abandon."
            + " Showing the base form.", abandons.message());
        assertTrue(local.verify("Abandoning").found());
        assertFalse(local.lookup("abandonment").success());
    }

    @Test
    void aRowThatIsAnInflectionPointsToItsBaseFormWhenItHasNoTranslation() throws Exception {
        LocalDictionaryService local = new LocalDictionaryService(imported(List.of(EcdictFixtures.ABANDON,
            "abandonedly,,,,,,,,0,0,0:abandon/1:x,,")));

        DictionaryLookupResult result = local.lookup("abandonedly");

        assertEquals("abandon", result.entries().get(0).english());
        assertEquals("Not in the local dictionary as written: abandonedly is a form of abandon. Showing the base form.",
            result.message());
    }

    @Test
    void reportsProgressInRowsAndBytesWhileImportingALargeFile() throws Exception {
        Path csv = EcdictFixtures.writeGenerated(tempDir.resolve("large.csv"), 25_000, "意义");
        List<EcdictImportProgress> updates = new ArrayList<>();

        EcdictMetadata imported = new EcdictImportService(repository()).importCsv(csv, updates::add, () -> false);

        assertEquals(25_000, imported.rowCount());
        long size = Files.size(csv);
        assertEquals(new EcdictImportProgress(0, 0, size), updates.get(0));
        assertEquals(new EcdictImportProgress(10_000, updates.get(1).bytesRead(), size), updates.get(1));
        assertEquals(new EcdictImportProgress(20_000, updates.get(2).bytesRead(), size), updates.get(2));
        assertEquals(new EcdictImportProgress(25_000, size, size), updates.get(3));
        assertEquals(4, updates.size());
        assertTrue(updates.get(1).bytesRead() > 0 && updates.get(1).bytesRead() < updates.get(2).bytesRead()
            && updates.get(2).bytesRead() < size, updates.toString());
        assertTrue(updates.get(1).fraction() > 0.3 && updates.get(1).fraction() < 0.5, updates.toString());
        String megabytes = String.format(java.util.Locale.ROOT, "%.1f MB", size / (1024.0 * 1024.0));
        assertEquals("Checking the file (" + megabytes + ")...", updates.get(0).toDisplayText());
        assertEquals("Read 25,000 rows (100% of " + megabytes + ")", updates.get(3).toDisplayText());
    }

    @Test
    void aCanceledImportKeepsThePreviousDictionaryAndLeavesNoHalfImportedFile() throws Exception {
        EcdictRepository repository = repository();
        EcdictImportService service = new EcdictImportService(repository);
        Path first = EcdictFixtures.write(tempDir.resolve("first.csv"), false, EcdictFixtures.REAL_ROWS);
        EcdictMetadata before = service.importCsv(first, progress -> { }, () -> false);
        LocalDictionaryService local = new LocalDictionaryService(repository);
        Path second = EcdictFixtures.writeGenerated(tempDir.resolve("second.csv"), 25_000, "新");
        AtomicBoolean cancel = new AtomicBoolean();
        List<EcdictImportProgress> updates = new ArrayList<>();

        assertThrows(CancellationException.class, () -> service.importCsv(second, progress -> {
            updates.add(progress);
            if (progress.rows() >= 10_000) {
                // Lookups still answer from the previous dictionary while the import runs.
                assertTrue(local.lookup("abandon").success());
                assertFalse(local.lookup(EcdictFixtures.generatedWord(5)).success());
                cancel.set(true);
            }
        }, cancel::get));

        assertEquals(10_000, updates.get(updates.size() - 1).rows());
        assertEquals(before, service.imported().orElseThrow());
        assertTrue(local.lookup("abandon").success());
        assertFalse(local.lookup(EcdictFixtures.generatedWord(5)).success());
        assertFalse(Files.exists(tempDir.resolve("ecdict.db.importing")));
        assertFalse(service.isImporting());

        // The next import is not blocked by the canceled one.
        assertEquals(25_000, service.importCsv(second, progress -> { }, () -> false).rowCount());
        assertEquals("新5; 测试; 检验", local.lookup(EcdictFixtures.generatedWord(5)).entries().get(0).chinese());
        assertFalse(local.lookup("abandon").success());
    }

    @Test
    void aFileThatCannotBeReadFailsWithItsLineAndKeepsThePreviousDictionary() throws Exception {
        EcdictRepository repository = repository();
        EcdictImportService service = new EcdictImportService(repository);
        EcdictMetadata before = service.importCsv(
            EcdictFixtures.write(tempDir.resolve("good.csv"), false, EcdictFixtures.REAL_ROWS), progress -> { }, () -> false);
        Path broken = tempDir.resolve("broken.csv");
        // 0xFF is valid neither in UTF-8 nor in GB18030.
        byte[] text = (EcdictFixtures.HEADER + "\r\nlucid,,,clear,,,,,,,,,\r\nbroken,,,").getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[text.length + 3];
        System.arraycopy(text, 0, content, 0, text.length);
        content[text.length] = (byte) 0xFF;
        content[text.length + 1] = '\r';
        content[text.length + 2] = '\n';
        Files.write(broken, content);

        IOException error = assertThrows(IOException.class, () -> service.importCsv(broken, progress -> { }, () -> false));

        assertTrue(error.getMessage().startsWith("Cannot import ECDICT CSV " + broken.toAbsolutePath() + ": Line 3: "),
            error.getMessage());
        assertEquals(before, service.imported().orElseThrow());
        assertTrue(new LocalDictionaryService(repository).lookup("abandon").success());
        assertFalse(Files.exists(tempDir.resolve("ecdict.db.importing")));
    }

    @Test
    void headersWithoutAWordOrMeaningColumnAndFilesWithoutEntriesAreExplained() throws Exception {
        EcdictImportService service = new EcdictImportService(repository());
        Path noMeaning = tempDir.resolve("no-meaning.csv");
        Files.writeString(noMeaning, "word,phonetic\nlucid,ˈluːsɪd\n");
        Path emptyMeanings = tempDir.resolve("empty-meanings.csv");
        Files.writeString(emptyMeanings, "word,translation\nlucid,\nabate,\n");
        Path empty = tempDir.resolve("empty.csv");
        Files.writeString(empty, "\n\n");

        assertEquals("Cannot import ECDICT CSV " + noMeaning.toAbsolutePath() + ": Line 1: the header row has no"
                + " Chinese meaning column (translation, chinese or 释义)",
            assertThrows(IOException.class, () -> service.importCsv(noMeaning, progress -> { }, () -> false)).getMessage());
        assertEquals("No dictionary entries in " + emptyMeanings.toAbsolutePath() + ": no row has both a word and a"
                + " Chinese meaning. Encoding: UTF-8 | Delimiter: comma | Columns: word, translation (header row)",
            assertThrows(IOException.class, () -> service.importCsv(emptyMeanings, progress -> { }, () -> false)).getMessage());
        assertEquals("The ECDICT CSV is empty: " + empty.toAbsolutePath(),
            assertThrows(IOException.class, () -> service.importCsv(empty, progress -> { }, () -> false)).getMessage());
        assertEquals("ECDICT CSV not found: " + tempDir.resolve("missing.csv").toAbsolutePath(),
            assertThrows(IOException.class,
                () -> service.importCsv(tempDir.resolve("missing.csv"), progress -> { }, () -> false)).getMessage());
        assertTrue(service.imported().isEmpty());
    }

    @Test
    void headerlessFilesAreReadInEcdictOrderOnlyWhenTheFourthFieldIsChinese() throws Exception {
        EcdictRepository repository = repository();
        EcdictImportService service = new EcdictImportService(repository);
        LocalDictionaryService local = new LocalDictionaryService(repository);
        Path ecdictOrder = tempDir.resolve("ecdict-no-header.csv");
        Files.writeString(ecdictOrder, "lucidx,ˈluːsɪd,clear,清晰的,adj\n");

        EcdictMetadata imported = service.importCsv(ecdictOrder, progress -> { }, () -> false);

        assertEquals("清晰的", local.lookup("lucidx").entries().get(0).chinese());
        assertTrue(imported.format().endsWith("(no header row, ECDICT column order)"), imported.format());

        // Review finding D7: english, chinese, pos, example without a header; the example is not a translation.
        Path wordList = tempDir.resolve("my-list.csv");
        Files.writeString(wordList, "lucidx,清晰的,adjective,The explanation was lucid.\nabatex,减弱,verb\n");

        imported = service.importCsv(wordList, progress -> { }, () -> false);

        DictionaryEntry lucid = local.lookup("lucidx").entries().get(0);
        assertEquals("清晰的", lucid.chinese());
        assertEquals("adjective", lucid.partOfSpeech());
        assertEquals("The explanation was lucid.", lucid.example());
        assertEquals("减弱", local.lookup("abatex").entries().get(0).chinese());
        assertEquals("Encoding: UTF-8 | Delimiter: comma | Columns: word, translation, pos, example, tag (no header row)",
            imported.format());
    }

    @Test
    void wordListsWithHeadersByteOrderMarksAndGbkAreRead() throws Exception {
        EcdictRepository repository = repository();
        EcdictImportService service = new EcdictImportService(repository);
        LocalDictionaryService local = new LocalDictionaryService(repository);
        Path excel = tempDir.resolve("excel-utf8.csv");
        Files.write(excel, ("﻿english,chinese,pos,example,tags\r\n"
            + "lucidx,清晰的,adjective,The storm.,gre\r\n").getBytes(StandardCharsets.UTF_8));

        service.importCsv(excel, progress -> { }, () -> false);

        DictionaryEntry lucid = local.lookup("lucidx").entries().get(0);
        assertEquals("清晰的", lucid.chinese());
        assertEquals("adjective", lucid.partOfSpeech());
        assertEquals("The storm.", lucid.example());

        Path unknownNames = tempDir.resolve("own-header.csv");
        Files.writeString(unknownNames, "Word,汉语解释\nlucidx,清晰的\n");
        service.importCsv(unknownNames, progress -> { }, () -> false);
        assertEquals("清晰的", local.lookup("lucidx").entries().get(0).chinese());
        assertEquals(1, service.imported().orElseThrow().rowCount());

        Path gbk = tempDir.resolve("gbk.csv");
        Files.write(gbk, "word,translation\r\nlucidx,\"清晰的\n易懂的\"\r\nabatex,减弱\r\n".getBytes(Charset.forName("GBK")));
        EcdictMetadata imported = service.importCsv(gbk, progress -> { }, () -> false);
        assertTrue(imported.format().startsWith("Encoding: GBK/GB18030 | "), imported.format());
        assertEquals("清晰的; 易懂的", local.lookup("lucidx").entries().get(0).chinese());
        assertEquals("减弱", local.lookup("abatex").entries().get(0).chinese());
    }

    @Test
    void theStateComparesPathSizeAndModificationTimeWithoutReadingTheFile() throws Exception {
        EcdictImportService service = new EcdictImportService(repository());
        Path csv = EcdictFixtures.write(tempDir.resolve("ecdict.csv"), false, EcdictFixtures.REAL_ROWS);
        Path other = EcdictFixtures.write(tempDir.resolve("other.csv"), false, EcdictFixtures.REAL_ROWS);
        assertEquals(EcdictImportService.State.NOT_IMPORTED, service.state(csv));

        service.importCsv(csv, progress -> { }, () -> false);

        assertEquals(EcdictImportService.State.UP_TO_DATE, service.state(csv));
        assertEquals(EcdictImportService.State.CHANGED, service.state(other));
        assertEquals(EcdictImportService.State.FILE_MISSING, service.state(tempDir.resolve("missing.csv")));
        // Same size, same time: not read, so not noticed. That is the point: starting the app never parses the CSV.
        FileTime modified = Files.getLastModifiedTime(csv);
        byte[] garbage = new byte[(int) Files.size(csv)];
        Files.write(csv, garbage);
        Files.setLastModifiedTime(csv, modified);
        assertEquals(EcdictImportService.State.UP_TO_DATE, service.state(csv));
        Files.setLastModifiedTime(csv, FileTime.fromMillis(modified.toMillis() + 2000));
        assertEquals(EcdictImportService.State.CHANGED, service.state(csv));
    }

    @Test
    void theDictionaryKeepsWorkingWhenTheCsvIsGoneAndAfterReopening() throws Exception {
        Path csv = EcdictFixtures.write(tempDir.resolve("ecdict.csv"), false, EcdictFixtures.REAL_ROWS);
        EcdictRepository first = repository();
        new EcdictImportService(first).importCsv(csv, progress -> { }, () -> false);
        first.close();
        Files.delete(csv);

        EcdictRepository reopened = repository();
        LocalDictionaryService local = new LocalDictionaryService(reopened);

        assertEquals("放弃; 抛弃; 遗弃; 使屈从; 沉溺; 放纵; 放任; 无拘束; 狂热", local.lookup("abandon").entries().get(0).chinese());
        assertEquals(6, local.status().ecdict().rowCount());
        assertEquals(EcdictImportService.State.FILE_MISSING, new EcdictImportService(reopened).state(csv));
    }

    @Test
    void checkReadsOnlyTheFirstRows() throws Exception {
        Path csv = EcdictFixtures.writeGenerated(tempDir.resolve("large.csv"), 1_000, "意义");
        // A broken line after the checked rows is not reached.
        Files.writeString(csv, "broken,\"never closed\r\n", java.nio.file.StandardOpenOption.APPEND);
        EcdictImportService service = new EcdictImportService(repository());

        EcdictCheck check = service.check(csv);

        assertEquals(EcdictImportService.CHECK_ROWS, check.rowsChecked());
        assertEquals(EcdictImportService.CHECK_ROWS, check.usableRows());
        assertEquals("wa: 意义0; 测试; 检验", check.sample());
        assertTrue(check.format().startsWith("Encoding: UTF-8 | Delimiter: comma | Columns: word, phonetic,"),
            check.format());
        assertTrue(service.imported().isEmpty(), "a check imports nothing");
    }

    @Test
    void deletingRemovesTheDictionaryFile() throws Exception {
        EcdictRepository repository = imported(EcdictFixtures.REAL_ROWS);
        EcdictImportService service = new EcdictImportService(repository);

        service.delete();

        assertFalse(Files.exists(tempDir.resolve("ecdict.db")));
        assertTrue(service.imported().isEmpty());
        assertFalse(new LocalDictionaryService(repository).lookup("abandon").success());
    }

    private EcdictRepository imported(List<String> rows) throws Exception {
        EcdictRepository repository = repository();
        Path csv = EcdictFixtures.write(tempDir.resolve("fixture.csv"), false, rows);
        new EcdictImportService(repository).importCsv(csv, progress -> { }, () -> false);
        return repository;
    }
}
