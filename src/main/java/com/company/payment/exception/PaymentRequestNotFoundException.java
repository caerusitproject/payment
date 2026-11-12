package com.company.payment.exception;

public class PaymentRequestNotFoundException extends RuntimeException {

    public PaymentRequestNotFoundException(Long paymentRequestId) {
        super("Payment request not found with ID: " + paymentRequestId);
    }
}
