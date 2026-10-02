package com.vocabtrainer.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
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

    @Test
    void headerlessStarterLayoutKeepsTheChineseWhenTheFirstRowHasAnExample() throws Exception {
        // english, chinese, pos, example without a header: the example is not a translation.
        Path csv = tempDir.resolve("my-list.csv");
        Files.writeString(csv, """
            lucidx,清晰的,adjective,The explanation was lucid.
            abatex,减弱,verb
            """);

        LocalDictionaryService service = new LocalDictionaryService(csv.toString());

        assertEquals("清晰的", service.lookup("lucidx").entries().get(0).chinese());
        assertEquals("adjective", service.lookup("lucidx").entries().get(0).partOfSpeech());
        assertEquals("减弱", service.lookup("abatex").entries().get(0).chinese());
        assertTrue(service.status().configuredPathLoaded());
    }

    @Test
    void readsAGreCsvSavedByExcelWithAByteOrderMark() throws Exception {
        Path csv = tempDir.resolve("excel-utf8.csv");
        Files.write(csv, ("\uFEFFenglish,chinese,pos,example,tags\r\n"
            + "lucidx,清晰的,adjective,The storm.,gre\r\n").getBytes(StandardCharsets.UTF_8));

        LocalDictionaryService service = new LocalDictionaryService(csv.toString());

        assertEquals("清晰的", service.lookup("lucidx").entries().get(0).chinese());
        assertEquals("adjective", service.lookup("lucidx").entries().get(0).partOfSpeech());
        assertEquals("The storm.", service.lookup("lucidx").entries().get(0).example());
    }

    @Test
    void readsGbkAndQuotedLineBreaks() throws Exception {
        Path csv = tempDir.resolve("gbk.csv");
        Files.write(csv, "word,translation\r\nlucidx,\"清晰的\n易懂的\"\r\nabatex,减弱\r\n".getBytes(Charset.forName("GBK")));

        LocalDictionaryService service = new LocalDictionaryService(csv.toString());

        assertTrue(service.status().configuredPathLoaded());
        assertEquals("清晰的\n易懂的", service.lookup("lucidx").entries().get(0).chinese());
        assertEquals("减弱", service.lookup("abatex").entries().get(0).chinese());
    }
}
