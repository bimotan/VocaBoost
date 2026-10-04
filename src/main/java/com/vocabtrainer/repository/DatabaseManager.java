package com.vocabtrainer.repository;

import com.vocabtrainer.util.DateTimeUtil;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the SQLite database file: creates and migrates the schema, hands out connections and runs
 * transactions.
 *
 * <p>Connections are pooled. {@link #getConnection()} lends one out and closing it returns it for
 * reuse, so repositories keep their try-with-resources style without opening the file each time.
 * Every connection is set up once with foreign keys on, a busy timeout and {@code synchronous=NORMAL};
 * the database itself uses write-ahead logging, so readers never block the writer.
 * {@link #close()} closes the pooled connections; the app calls it on exit and tests after each
 * test, so no file handle stays open.
 */
public class DatabaseManager implements TransactionRunner, AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(DatabaseManager.class.getName());
    /** How long a statement waits for another connection's write lock before failing with SQLITE_BUSY. */
    public static final int BUSY_TIMEOUT_MILLIS = 5000;
    /** Idle connections kept for reuse. The FX thread and one or two background tasks are the usual load. */
    private static final int MAX_IDLE_CONNECTIONS = 4;

    private final Path databasePath;
    private final String jdbcUrl;
    private final ThreadLocal<Transaction> activeTransaction = new ThreadLocal<>();
    private final Object poolLock = new Object();
    /** Most recently returned first, guarded by poolLock. */
    private final Deque<Connection> idleConnections = new ArrayDeque<>();
    /** Idle and lent-out connections, guarded by poolLock. */
    private int openConnections;
    /** Guarded by poolLock. */
    private long connectionsOpened;
    /** Guarded by poolLock. */
    private boolean closed;

    public DatabaseManager() {
        this(DateTimeUtil.defaultDatabasePath());
    }

    public DatabaseManager(Path databasePath) {
        this.databasePath = databasePath;
        this.jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath();
    }

    /**
     * Creates the database file if needed and migrates its schema to the current version; see
     * {@link SchemaMigrations}. Safe to call on every start and on a database of any earlier version.
     */
    public void initialize() throws SQLException {
        try {
            Path parent = databasePath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new SQLException("Cannot create the database folder for " + databasePath, e);
        }

        Lease setup = lease();
        boolean migrated = false;
        try {
            enableWriteAheadLog(setup.view);
            new SchemaMigrations(setup.view).migrate();
            migrated = true;
        } finally {
            if (migrated) {
                setup.release();
            } else {
                // A failed step may have left foreign keys off on this connection.
                setup.discard();
            }
        }
    }

    /**
     * Write-ahead logging lets the FX thread read while a background task writes, and with
     * {@code synchronous=NORMAL} a commit needs no fsync of the main file. The mode is stored in
     * the database file, so setting it once is enough; it cannot change inside a transaction.
     */
    private void enableWriteAheadLog(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA journal_mode = WAL")) {
            String mode = rs.next() ? rs.getString(1) : null;
            if (!"wal".equalsIgnoreCase(mode)) {
                // For example on a file system without shared-memory support; the database still works.
                LOGGER.warning("SQLite kept journal_mode=" + mode + " instead of WAL for " + databasePath);
            }
        }
    }

    /**
     * Lends out a pooled connection, or, while this thread is inside {@link #inTransaction}, returns
     * the transaction's connection. Closing a pooled connection returns it to the pool; closing the
     * transaction's connection is a no-op. Either way repositories keep using try-with-resources.
     */
    public Connection getConnection() throws SQLException {
        Transaction transaction = activeTransaction.get();
        if (transaction != null) {
            return transaction.participant;
        }
        return lease().view;
    }

    /**
     * Runs {@code work} in one transaction bound to the current thread. Every
     * {@link #getConnection()} call the work makes on this thread joins it, and a nested
     * {@code inTransaction} call joins the outer one. The outermost call commits when the work
     * returns and rolls back if it throws anything; a failed nested call makes the whole
     * transaction roll back even if the outer work catches the exception.
     */
    @Override
    public <T> T inTransaction(SqlWork<T> work) throws SQLException {
        Transaction current = activeTransaction.get();
        if (current != null) {
            try {
                return work.run();
            } catch (Throwable e) {
                current.rollbackOnly = true;
                throw e;
            }
        }

        Lease lease = lease();
        Connection connection = lease.view;
        Transaction transaction = new Transaction(connection);
        activeTransaction.set(transaction);
        boolean started = false;
        boolean reusable = true;
        try {
            // BEGIN IMMEDIATE takes the write lock up front: if another connection is writing, this
            // waits for the busy timeout instead of failing at once when a read is later upgraded
            // to a write. Plain SQL is used instead of setAutoCommit(false)/commit() because the
            // driver's commit() immediately opens the next transaction.
            execute(connection, "BEGIN IMMEDIATE");
            started = true;
            T result = work.run();
            if (transaction.rollbackOnly) {
                throw new SQLException("Transaction rolled back: a nested operation failed or requested a rollback");
            }
            execute(connection, "COMMIT");
            return result;
        } catch (Throwable e) {
            if (started) {
                reusable = rollbackQuietly(connection, e);
            }
            throw e;
        } finally {
            activeTransaction.remove();
            if (reusable) {
                lease.release();
            } else {
                // The transaction may still be open on it; closing the connection ends it.
                lease.discard();
            }
        }
    }

    /**
     * Closes the pooled connections. Connections still lent out are closed when they are returned,
     * and asking for a connection afterwards fails. Closing twice does nothing.
     */
    @Override
    public void close() {
        List<Connection> idle;
        synchronized (poolLock) {
            closed = true;
            idle = new ArrayList<>(idleConnections);
            idleConnections.clear();
            openConnections -= idle.size();
        }
        idle.forEach(DatabaseManager::closeQuietly);
    }

    /** Physical connections opened since this manager was created; for diagnostics and tests. */
    public long connectionsOpened() {
        synchronized (poolLock) {
            return connectionsOpened;
        }
    }

    /** Physical connections open right now, idle or lent out. Zero after {@link #close()} unless one leaked. */
    public int openConnectionCount() {
        synchronized (poolLock) {
            return openConnections;
        }
    }

    private Lease lease() throws SQLException {
        Connection connection;
        synchronized (poolLock) {
            if (closed) {
                throw new SQLException("Database is closed: " + databasePath);
            }
            connection = idleConnections.pollFirst();
            if (connection == null) {
                // Counted before opening so close() never sees fewer connections than exist.
                openConnections++;
            }
        }
        if (connection == null) {
            try {
                connection = openConnection();
            } catch (SQLException | RuntimeException | Error e) {
                synchronized (poolLock) {
                    openConnections--;
                }
                throw e;
            }
        }
        return new Lease(connection);
    }

    private Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MILLIS);
            // Safe with WAL: a crash can lose the last commits but never corrupts the database.
            statement.execute("PRAGMA synchronous = NORMAL");
        } catch (SQLException e) {
            closeQuietly(connection);
            throw e;
        }
        synchronized (poolLock) {
            connectionsOpened++;
        }
        return connection;
    }

    private void giveBack(Connection connection, boolean reusable) {
        boolean pooled;
        synchronized (poolLock) {
            pooled = reusable && !closed && idleConnections.size() < MAX_IDLE_CONNECTIONS;
            if (pooled) {
                idleConnections.addFirst(connection);
            } else {
                openConnections--;
            }
        }
        if (!pooled) {
            closeQuietly(connection);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** Returns whether the rollback worked, that is whether the connection is safe to reuse. */
    private static boolean rollbackQuietly(Connection connection, Throwable failure) {
        try {
            execute(connection, "ROLLBACK");
            return true;
        } catch (SQLException rollbackError) {
            // SQLite may already have rolled back on its own; closing the connection finishes the job.
            failure.addSuppressed(rollbackError);
            return false;
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException e) {
            // The outcome is already decided; a close failure must not turn a commit into an error.
            LOGGER.log(Level.WARNING, "Cannot close database connection", e);
        }
    }

    public Path getDatabasePath() {
        return databasePath;
    }

    /**
     * One loan of a pooled connection. Code gets {@link #view}: closing it closes the statements
     * the borrower left open and returns the connection to the pool, exactly once; using it after
     * that fails like using a closed connection.
     */
    private final class Lease {
        private final Connection connection;
        private final Connection view;
        private final List<Statement> statements = new ArrayList<>();
        private boolean returned;

        private Lease(Connection connection) {
            this.connection = connection;
            this.view = (Connection) Proxy.newProxyInstance(
                DatabaseManager.class.getClassLoader(), new Class<?>[] {Connection.class}, this::invoke);
        }

        private Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "close":
                    release();
                    return null;
                case "isClosed":
                    return returned || connection.isClosed();
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "pooled " + connection;
                default:
                    break;
            }
            if (returned) {
                throw new SQLException("Connection is closed");
            }
            Object result;
            try {
                result = method.invoke(connection, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            if (result instanceof Statement statement) {
                track(statement);
            }
            return result;
        }

        private void track(Statement statement) {
            if (statements.size() >= 32) {
                statements.removeIf(Lease::isClosedOrBroken);
            }
            statements.add(statement);
        }

        /** Returns the connection to the pool, or closes it if it cannot be reset to a clean state. */
        private void release() {
            if (returned) {
                return;
            }
            returned = true;
            boolean reusable = closeStatements() && resetAutoCommit();
            giveBack(connection, reusable);
        }

        /** Closes the connection instead of pooling it. */
        private void discard() {
            if (returned) {
                return;
            }
            returned = true;
            statements.clear();
            giveBack(connection, false);
        }

        private boolean closeStatements() {
            boolean clean = true;
            for (Statement statement : statements) {
                try {
                    statement.close();
                } catch (SQLException e) {
                    clean = false;
                }
            }
            statements.clear();
            return clean;
        }

        /** A borrower that turned auto-commit off and did not finish must not leave its transaction open. */
        private boolean resetAutoCommit() {
            try {
                if (connection.isClosed()) {
                    return false;
                }
                if (!connection.getAutoCommit()) {
                    connection.rollback();
                    connection.setAutoCommit(true);
                }
                return true;
            } catch (SQLException e) {
                LOGGER.log(Level.WARNING, "Discarding a database connection that cannot be reset", e);
                return false;
            }
        }

        private static boolean isClosedOrBroken(Statement statement) {
            try {
                return statement.isClosed();
            } catch (SQLException e) {
                return true;
            }
        }
    }

    /** The connection bound to one outermost {@link #inTransaction} call. */
    private static final class Transaction {
        private final Connection connection;
        private final Connection participant;
        private boolean rollbackOnly;

        private Transaction(Connection connection) {
            this.connection = connection;
            this.participant = participantView();
        }

        /**
         * The connection as handed to code running inside the transaction: the outermost
         * inTransaction call owns commit and close (which returns the connection to the pool), so
         * those calls are ignored, and a rollback marks the whole transaction for rollback.
         */
        private Connection participantView() {
            InvocationHandler handler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "close", "commit", "setAutoCommit":
                        return null;
                    case "getAutoCommit":
                        return false;
                    case "rollback":
                        if (method.getParameterCount() == 0) {
                            rollbackOnly = true;
                            return null;
                        }
                        break;
                    case "equals":
                        return proxy == args[0];
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    default:
                        break;
                }
                try {
                    return method.invoke(connection, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            };
            return (Connection) Proxy.newProxyInstance(
                DatabaseManager.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
        }
    }
}
