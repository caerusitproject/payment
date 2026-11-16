package com.company.payment.service;

import com.company.payment.exception.PaymentExecutionLockException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * SQL Server application lock shared by all Payment Service instances connected to the same DB.
 * Session ownership keeps the lock for the lifetime of this dedicated JDBC connection.
 * Pending inquiry processing intentionally does not acquire this lock.
 */

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * SQL Server distributed application lock for Payment Service.
 *
 * Purpose:
 * Ensures that only one Payment Service instance executes
 * the protected payment workflow at a time.
 *
 * All instances must use:
 * 1. The same SQL Server database.
 * 2. The same lock resource name.
 *
 * Pending inquiry processing intentionally does not use this lock.
 */
@Component
@RequiredArgsConstructor
public class PaymentExecutionLock {

    /**
     * Unique name identifying our application lock.
     *
     * Scheduler and RetryQueueConsumer use this same resource name.
     * Therefore, they compete for the same SQL Server lock.
     */
    private static final String RESOURCE =
            "payment-service-sequential-execution";

    /**
     * DataSource provides JDBC connections to SQL Server.
     *
     * We use one dedicated connection for acquiring the lock,
     * executing the protected work, and releasing the lock.
     */
    private final DataSource dataSource;


    /**
     * Attempts to acquire the SQL Server application lock.
     *
     * @param work payment workflow that should execute only
     *             after successfully acquiring the lock
     *
     * @return true  - lock acquired and work executed
     *         false - another process already holds the lock
     */
    public boolean executeIfAcquired(Runnable work) {

        /*
         * STEP 1:
         * Obtain a JDBC connection from the DataSource.
         *
         * try-with-resources automatically closes the connection
         * when this method exits.
         *
         * IMPORTANT:
         * SQL Server session-owned application locks belong to
         * this database connection's session.
         */
        try (Connection connection = dataSource.getConnection()) {

            /*
             * STEP 2:
             * Enable auto-commit mode.
             *
             * SQL statements execute without requiring an explicit
             * transaction commit.
             *
             * The application lock is session-owned, not
             * transaction-owned.
             */
            connection.setAutoCommit(true);


            /*
             * STEP 3:
             * Ask SQL Server to acquire the application lock.
             *
             * If another process already holds the same exclusive
             * lock, tryLock() returns false.
             *
             * @LockTimeout = 0 means do not wait.
             * Return immediately if the lock is unavailable.
             */
            if (!tryLock(connection)) {

                /*
                 * Another scheduler or retry consumer currently
                 * owns the lock.
                 *
                 * Skip this execution.
                 * The caller's polling/redispatch mechanism must
                 * provide another opportunity to process the work.
                 */
                return false;
            }


            /*
             * STEP 4:
             * The lock has been acquired successfully.
             *
             * Execute the supplied payment workflow.
             *
             * Examples:
             * - workflow.tick()
             * - workflow.processRetryEvent(...)
             *
             * The SQL Server lock remains held while work.run()
             * is executing.
             */
            try {

                work.run();

                /*
                 * STEP 5:
                 * Workflow completed without throwing an exception.
                 * Return true to indicate successful execution.
                 */
                return true;

            } finally {

                /*
                 * STEP 6:
                 * Always attempt to release the lock.
                 *
                 * The finally block executes even if work.run()
                 * throws a RuntimeException.
                 */
                unlock(connection);
            }

        } catch (SQLException e) {

            /*
             * STEP 7:
             * Handle JDBC/SQL Server errors, including errors
             * while acquiring or releasing the application lock.
             */
            throw new PaymentExecutionLockException(
                    "Could not acquire or release the SQL Server payment execution lock",
                    e
            );
        }
    }


    /**
     * Requests an exclusive application lock from SQL Server.
     *
     * @param connection current JDBC connection
     * @return true if the lock is acquired; otherwise false
     */
    private boolean tryLock(Connection connection) throws SQLException {

        /*
         * sp_getapplock is a built-in SQL Server stored procedure.
         *
         * Parameters:
         *
         * @Resource:
         *   Identifies the application lock by its unique name.
         *
         * @LockMode = 'Exclusive':
         *   Only one session can hold this exclusive lock.
         *
         * @LockOwner = 'Session':
         *   The lock belongs to the current SQL Server session.
         *   It remains held until explicitly released or the
         *   session ends.
         *
         * @LockTimeout = 0:
         *   Do not wait if another session holds the lock.
         *
         * SQL Server result:
         *   Non-negative = lock successfully acquired.
         *   Negative     = lock acquisition failed.
         */
        String sql = """
                DECLARE @result int;

                EXEC @result = sp_getapplock
                    @Resource = ?,
                    @LockMode = 'Exclusive',
                    @LockOwner = 'Session',
                    @LockTimeout = 0;

                SELECT @result;
                """;


        /*
         * STEP 1:
         * Prepare the SQL statement.
         *
         * The question mark is a parameter placeholder.
         * We bind the resource name separately.
         */
        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            /*
             * STEP 2:
             * Set the application lock resource name.
             */
            statement.setString(1, RESOURCE);


            /*
             * STEP 3:
             * Execute the SQL statement.
             *
             * SQL Server attempts to acquire the exclusive lock.
             */
            try (ResultSet rs = statement.executeQuery()) {

                /*
                 * STEP 4:
                 * Read SQL Server's return code.
                 *
                 * A non-negative result means the lock was acquired.
                 * A negative result means it was not acquired.
                 */
                return rs.next() && rs.getInt(1) >= 0;
            }
        }
    }


    /**
     * Releases the previously acquired SQL Server application lock.
     *
     * @param connection same JDBC connection used to acquire the lock
     */
    private void unlock(Connection connection) throws SQLException {

        /*
         * sp_releaseapplock releases the application lock.
         *
         * @Resource:
         *   Must match the resource name used during acquisition.
         *
         * @LockOwner = 'Session':
         *   Releases the session-owned application lock.
         *
         * IMPORTANT:
         * We must use the same SQL Server session that acquired it.
         */
        String sql = """
                DECLARE @result int;

                EXEC @result = sp_releaseapplock
                    @Resource = ?,
                    @LockOwner = 'Session';

                SELECT @result;
                """;


        /*
         * STEP 1:
         * Prepare the release statement.
         */
        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            /*
             * STEP 2:
             * Set the same resource name.
             */
            statement.setString(1, RESOURCE);


            /*
             * STEP 3:
             * Execute the release operation.
             *
             * Consume the result before closing the statement.
             */
            try (ResultSet ignored = statement.executeQuery()) {

                // SQL Server release result is consumed here.
            }
        }
    }
}
