
package com.company.payment.bank;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record BalanceInquiryRequest(

        @NotBlank(message = "Account number is required")
        @Pattern(
                regexp = "\\d{12}",
                message = "Account number must contain exactly 12 digits"
        )
        String accountNo

) {
}
