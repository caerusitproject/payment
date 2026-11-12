package com.company.payment.exception;

/** Raised when an original payment request has no persisted bank transaction attempt. */
public class PaymentTransactionNotFoundException extends RuntimeException {

    public PaymentTransactionNotFoundException(Long paymentRequestId) {
        super("No payment transaction found for payment request ID: " + paymentRequestId);
    }
}
