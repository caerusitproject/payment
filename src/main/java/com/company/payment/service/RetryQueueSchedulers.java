package com.company.payment.service;

import com.company.payment.domain.enums.QueueType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Three independent DB-backed retry schedules. No retry Kafka topics are used. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryQueueSchedulers {
    private final PaymentWorkflowService workflow;
    private final RetrySchedulerExecutionGuard guard;

    @Scheduled(fixedDelayString = "${payment.retry.scheduler.retry1-interval-ms:900000}")
    public void runRetry1() {
        guard.runIfAvailable(QueueType.RETRY_1,
                () -> workflow.processDueRetryQueue(QueueType.RETRY_1));
    }

    @Scheduled(fixedDelayString = "${payment.retry.scheduler.retry2-interval-ms:3600000}")
    public void runRetry2() {
        guard.runIfAvailable(QueueType.RETRY_2,
                () -> workflow.processDueRetryQueue(QueueType.RETRY_2));
    }

    @Scheduled(fixedDelayString = "${payment.retry.scheduler.retry3-interval-ms:14400000}")
    public void runRetry3() {
        guard.runIfAvailable(QueueType.RETRY_3,
                () -> workflow.processDueRetryQueue(QueueType.RETRY_3));
    }
}
