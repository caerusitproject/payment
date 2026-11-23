package com.company.payment.domain.enums;

/** Payment request lifecycle. RETRY_1/2/3 are scheduler-selected business statuses. */
public enum ProcStatus {
    QUEUED, IN_PROGRESS, AWAITING_OTP, PENDING,
    RETRY_1, RETRY_2, RETRY_3,
    RETRY_WAIT, SUCCESS, FAILED, MANUAL_INTERVENTION
}
