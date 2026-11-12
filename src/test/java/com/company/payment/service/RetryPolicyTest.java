package com.company.payment.service;

import static org.junit.jupiter.api.Assertions.*;

import com.company.payment.config.PaymentProperties;
import com.company.payment.domain.enums.QueueType;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(new PaymentProperties(null, null,
            new PaymentProperties.Retry(List.of(Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofHours(2)), 3),
            null, null, "INR", false, null));

    @Test void firstFailureGoesToRetryQueue1After15Min() {
        var step = policy.next(0).orElseThrow();
        assertEquals(QueueType.RETRY_1, step.queue());
        assertEquals(Duration.ofMinutes(15), step.delay());
    }

    @Test void secondAndThirdQueues() {
        assertEquals(Duration.ofMinutes(30), policy.next(1).orElseThrow().delay());
        assertEquals(QueueType.RETRY_3, policy.next(2).orElseThrow().queue());
        assertEquals(Duration.ofHours(2), policy.next(2).orElseThrow().delay());
    }

    @Test void afterThirdRetryNothingLeft() {
        assertTrue(policy.next(3).isEmpty());
    }

    @Test void retryNumberFromQueue() {
        assertEquals(0, RetryPolicy.retryNumber(QueueType.NEW));
        assertEquals(2, RetryPolicy.retryNumber(QueueType.RETRY_2));
    }
}
