package com.company.payment.exception;

public class OtpAlreadyConsumedException extends RuntimeException {

    public OtpAlreadyConsumedException(String message) {
        super(message);
    }
}
