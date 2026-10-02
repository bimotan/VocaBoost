package com.vocabtrainer.repository;

import java.sql.SQLException;

/**
 * Runs several repository calls as one database transaction.
 *
 * <p>Repositories called from inside the work join the transaction automatically, so their
 * signatures do not change. Either everything the work wrote is committed, or nothing is.
 */
public interface TransactionRunner {

    <T> T inTransaction(SqlWork<T> work) throws SQLException;

    @FunctionalInterface
    interface SqlWork<T> {
        T run() throws SQLException;
    }
}
