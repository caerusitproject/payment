package com.company.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * OTP submitted by an authorized Accounts user for the currently active transaction.
 * OTP values must never be logged or included in exception messages.
 */
public record OtpSubmitRequest(
        @NotNull(message = "Transaction ID is required")
        Long transactionId,

        @NotBlank(message = "OTP is required")
        @Pattern(regexp = "\\d{4,10}", message = "OTP must contain 4-10 digits")
        String otp
) {
}
