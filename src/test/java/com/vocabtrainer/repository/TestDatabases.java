package com.vocabtrainer.repository;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Opens the databases a test uses and closes them after it, before {@code @TempDir} is deleted:
 * a connection left open would keep the file locked on Windows. Fails the test if code under
 * test kept a connection without closing it.
 *
 * <pre>{@code @RegisterExtension final TestDatabases databases = new TestDatabases();}</pre>
 */
public final class TestDatabases implements AfterEachCallback {
    private final List<DatabaseManager> managers = new ArrayList<>();

    /** An initialized database at {@code file}. */
    public DatabaseManager open(Path file) throws SQLException {
        DatabaseManager manager = track(new DatabaseManager(file));
        manager.initialize();
        return manager;
    }

    /** Closes {@code manager} after the test; for managers the test creates itself. */
    public <T extends DatabaseManager> T track(T manager) {
        managers.add(manager);
        return manager;
    }

    @Override
    public void afterEach(ExtensionContext context) {
        List<String> leaks = new ArrayList<>();
        for (DatabaseManager manager : managers) {
            manager.close();
            if (manager.openConnectionCount() != 0) {
                leaks.add(manager.getDatabasePath() + ": " + manager.openConnectionCount() + " connection(s) never closed");
            }
        }
        managers.clear();
        if (!leaks.isEmpty()) {
            fail("Database connections leaked: " + String.join("; ", leaks));
        }
    }
}
