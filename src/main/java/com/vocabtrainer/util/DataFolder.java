package com.vocabtrainer.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The folder that holds the database, the ECDICT import and the logs. The database holds a saved AI
 * API key, so where the file system has POSIX permissions (Linux, macOS) the folder is made
 * accessible to its owner only (700). Elsewhere (Windows) nothing is changed: the folder lies in
 * the user's profile, which other accounts cannot open by default. A failure is logged and never
 * stops the app.
 */
public final class DataFolder {
    static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rwx------");
    private static final Logger LOGGER = Logger.getLogger(DataFolder.class.getName());

    private DataFolder() {
    }

    /**
     * Creates {@code folder} if needed and restricts it to its owner where the file system allows.
     *
     * @return whether the folder is now accessible to its owner only; false where that cannot be
     *         expressed (no POSIX permissions) or setting it failed
     */
    public static boolean prepare(Path folder) {
        try {
            Files.createDirectories(folder);
            PosixFileAttributeView posix = Files.getFileAttributeView(folder, PosixFileAttributeView.class);
            if (posix == null) {
                return false;
            }
            if (!posix.readAttributes().permissions().equals(OWNER_ONLY)) {
                posix.setPermissions(OWNER_ONLY);
            }
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            LOGGER.log(Level.WARNING, "Cannot restrict the data folder " + folder + " to its owner", e);
            return false;
        }
    }
}
