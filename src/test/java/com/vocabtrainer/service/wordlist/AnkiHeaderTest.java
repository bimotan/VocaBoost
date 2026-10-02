package com.vocabtrainer.service.wordlist;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnkiHeaderTest {
    @Test
    void theHeadersOfAnAnkiNotesExportAreRead() {
        // What Anki 2.1.55+ writes for "Notes in Plain Text" with every option ticked, without the '#'.
        AnkiHeader header = AnkiHeader.parse(List.of(
            "separator:tab", "html:true", "guid column:1", "notetype column:2", "deck column:3", "tags column:6"));

        assertTrue(header.isAnki());
        assertEquals(6, header.lineCount());
        assertEquals(Optional.of('\t'), header.separator());
        assertEquals(AnkiHeader.Html.HTML, header.html());
        assertEquals(5, header.tagsColumn());
        assertEquals(Set.of(0, 1, 2), header.metadataColumns());
        assertEquals(Optional.of("Anki guid"), header.columnRole(0));
        assertEquals(Optional.of("Anki note type"), header.columnRole(1));
        assertEquals(Optional.of("Anki deck"), header.columnRole(2));
        assertEquals(Optional.of("Anki tags"), header.columnRole(5));
        assertEquals(Optional.empty(), header.columnRole(3));
        assertFalse(header.hasColumnNames());
        assertEquals("Anki headers: deck column, guid column, html, notetype column, separator, tags column",
            header.describe());
    }

    @Test
    void separatorsAreNamedOrGivenAsTheCharacterInAnyCase() {
        assertEquals(Optional.of('\t'), separator("Tab"));
        assertEquals(Optional.of(','), separator("comma"));
        assertEquals(Optional.of(';'), separator("Semicolon"));
        assertEquals(Optional.of(' '), separator("Space"));
        assertEquals(Optional.of('|'), separator("PIPE"));
        assertEquals(Optional.of(':'), separator("colon"));
        assertEquals(Optional.of('|'), separator("|"));
        assertEquals(Optional.of('\t'), separator("\t"));
        assertEquals(Optional.of(' '), separator(" "));
        assertEquals(Optional.of('#'), separator("#"));
        assertEquals(Optional.empty(), separator("tabs and commas"));
        assertEquals(Optional.empty(), separator(""));
    }

    @Test
    void columnNamesAreSplitAtTheSeparatorAndGlobalTagsAtSpaces() {
        AnkiHeader header = AnkiHeader.parse(List.of(
            "separator:Pipe", "html:false", "columns:Front|Back| Tags ", "tags column:3", "tags:gre  vocab::core",
            "deck:GRE 3000", "notetype:Basic", "if matches:update current"));

        assertEquals(List.of("Front", "Back", "Tags"), header.columnNames('|'));
        assertEquals(AnkiHeader.Html.TEXT, header.html());
        assertEquals(List.of("gre", "vocab::core"), header.tags());
        assertEquals(2, header.tagsColumn());
        assertEquals(Set.of(), header.metadataColumns(), "a global deck or note type takes no column");
    }

    @Test
    void commentsAndUnknownOrBrokenHeadersAreSkippedWithoutMakingTheFileAnAnkiFile() {
        AnkiHeader comments = AnkiHeader.parse(List.of(" My GRE list", "exported by hand", "future option:1"));
        assertFalse(comments.isAnki());
        assertEquals(3, comments.lineCount());
        assertEquals(AnkiHeader.Html.DETECT, comments.html());
        assertEquals("", comments.describe());

        AnkiHeader broken = AnkiHeader.parse(List.of("html:maybe", "tags column:zero", "deck column:0"));
        assertFalse(broken.isAnki());
        assertEquals(AnkiHeader.Html.DETECT, broken.html());
        assertEquals(-1, broken.tagsColumn());
        assertEquals(Set.of(), broken.metadataColumns());
        assertFalse(AnkiHeader.NONE.isAnki());
    }

    private static Optional<Character> separator(String value) {
        return AnkiHeader.parse(List.of("separator:" + value)).separator();
    }
}
