package com.company.payment.bank;

import java.math.BigDecimal;

/** Transaction-with-OTP request. Contains account numbers and the OTP: never log it. */
public record SubmitRequest(String uniqueId, String debitAccount, String creditAccount, String ifsc,
                            BigDecimal amount, String currency, String transactionType, String payeeName,
                            String remarks, String otp, Long benAcctId) {
    @Override public String toString() { return "SubmitRequest{uniqueId=" + uniqueId + "}"; }
}
