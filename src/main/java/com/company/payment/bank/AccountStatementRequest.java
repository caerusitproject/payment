
package com.company.payment.bank;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;

public record AccountStatementRequest(

        @NotBlank(message = "Account number is required")
        @Pattern(
                regexp = "\\d{12}",
                message = "Account number must contain exactly 12 digits"
        )
        String accountNo,

        @NotNull(message = "From date is required")
        LocalDate fromDate,

        @NotNull(message = "To date is required")
        LocalDate toDate

) {
}
