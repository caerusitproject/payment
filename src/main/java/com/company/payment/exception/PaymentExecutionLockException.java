package com.company.payment.exception;

public class PaymentExecutionLockException extends RuntimeException {

    public PaymentExecutionLockException(String message) {
        super(message);
    }

    public PaymentExecutionLockException(String message, Throwable cause) {
        super(message, cause);
    }
}
