package com.company.payment.exception;

public class NoEligiblePaymentModeException extends RuntimeException {

    public NoEligiblePaymentModeException(String message) {
        super(message);
    }
}
