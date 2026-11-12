package com.company.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls the NEW payment queue while holding the cluster-wide PostgreSQL execution lock. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentProcessorScheduler {
    private final PaymentWorkflowService workflow;
    private final PaymentExecutionLock executionLock;

    @Scheduled(fixedDelayString = "${payment.processor.poll-interval-ms}")
    public void poll() {
        try {
            executionLock.executeIfAcquired(workflow::tick);
        } catch (Exception e) {
            log.error("Payment processor tick failed; the next poll will recover", e);
        }
    }
}
