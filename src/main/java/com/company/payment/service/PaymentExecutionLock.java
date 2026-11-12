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
 * PostgreSQL session-level advisory lock shared by all service instances.
 * It serializes new/retry payment execution and OTP worklist creation across pods.
 * Pending inquiry consumers deliberately do not use this lock.
 */
@Component
@RequiredArgsConstructor
public class PaymentExecutionLock {
    private static final long LOCK_KEY = 7_204_202_611L;
    private final DataSource dataSource;

    public boolean executeIfAcquired(Runnable work) {
        try (Connection connection = dataSource.getConnection()) {
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
            throw new PaymentExecutionLockException("Could not acquire or release the payment execution lock", e);
        }
    }

    private boolean tryLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private void unlock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_unlock(?)")) {
            statement.setLong(1, LOCK_KEY);
            statement.executeQuery().close();
        }
    }
}
