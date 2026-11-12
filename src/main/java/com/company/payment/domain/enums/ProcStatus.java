package com.company.payment.domain.enums;

/** Processing state of a payment request. */
public enum ProcStatus {
    QUEUED, IN_PROGRESS, AWAITING_OTP, PENDING, RETRY_WAIT, SUCCESS, FAILED, MANUAL_INTERVENTION
}
