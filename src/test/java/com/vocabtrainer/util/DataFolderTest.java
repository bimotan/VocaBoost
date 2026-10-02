package com.vocabtrainer.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DataFolderTest {
    @TempDir
    Path tempDir;

    @Test
    void onPosixFileSystemsOnlyTheOwnerCanOpenTheFolder() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "POSIX permissions");
        Path existing = Files.createDirectory(tempDir.resolve(".vocab-trainer"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
        Path missing = tempDir.resolve("new").resolve(".vocab-trainer");

        assertTrue(DataFolder.prepare(existing));
        assertTrue(DataFolder.prepare(missing));

        assertEquals(DataFolder.OWNER_ONLY, Files.getPosixFilePermissions(existing));
        assertEquals(DataFolder.OWNER_ONLY, Files.getPosixFilePermissions(missing));
        assertTrue(DataFolder.prepare(existing), "an already private folder stays as it is");
    }

    @Test
    void withoutPosixPermissionsTheFolderIsCreatedAndLeftAsItIs() throws Exception {
        // A zip file system has no POSIX permissions, like NTFS on Windows.
        URI zip = URI.create("jar:" + tempDir.resolve("data.zip").toUri());
        try (FileSystem fileSystem = FileSystems.newFileSystem(zip, Map.of("create", "true"))) {
            Path folder = fileSystem.getPath("/.vocab-trainer");

            assertFalse(DataFolder.prepare(folder));
            assertTrue(Files.isDirectory(folder));
        }
    }
}
