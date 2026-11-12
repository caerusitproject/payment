package com.company.payment.worklist;

/** State of the OTP work item of one transaction. {@code otp} is only set when {@code state == COMPLETED}. */
public record OtpStatus(State state, String otp) {
    public enum State { OPEN, COMPLETED, EXPIRED, CONSUMED, MISSING }
}
