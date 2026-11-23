package com.company.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Checks and cancels expired OTP work items; it does not poll the NEW payment queue. */
@Slf4j
@Component
@RequiredArgsConstructor
public class OtpExpiryScheduler {
    private final PaymentWorkflowService workflow;

    @Scheduled(fixedDelayString = "${payment.otp.expiry-check-interval-ms:120000}")
    public void expireOtpTasks() {
        try {
            workflow.expireAwaitingOtpRequests();
        } catch (Exception e) {
            log.error("OTP expiry check failed; it will be retried on the next interval", e);
        }
    }
}
