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
@Component
@RequiredArgsConstructor
public class PaymentExecutionLock {
    private static final String RESOURCE = "payment-service-sequential-execution";
    private final DataSource dataSource;

    public boolean executeIfAcquired(Runnable work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            if (!tryLock(connection)) {
                return false;
            }
            try {
                work.run();
                return true;
            } finally {
                unlock(connection);
            }
        } catch (SQLException e) {
            throw new PaymentExecutionLockException(
                    "Could not acquire or release the SQL Server payment execution lock", e);
        }
    }

    private boolean tryLock(Connection connection) throws SQLException {
        String sql = """
                DECLARE @result int;
                EXEC @result = sp_getapplock
                    @Resource = ?,
                    @LockMode = 'Exclusive',
                    @LockOwner = 'Session',
                    @LockTimeout = 0;
                SELECT @result;
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, RESOURCE);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getInt(1) >= 0;
            }
        }
    }

    private void unlock(Connection connection) throws SQLException {
        String sql = """
                DECLARE @result int;
                EXEC @result = sp_releaseapplock
                    @Resource = ?,
                    @LockOwner = 'Session';
                SELECT @result;
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, RESOURCE);
            try (ResultSet ignored = statement.executeQuery()) {
                // Result is intentionally consumed before the JDBC session is closed.
            }
        }
    }
}
