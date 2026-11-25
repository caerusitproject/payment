
package com.company.payment.dto.response;

import com.fasterxml.jackson.databind.JsonNode;

public record BankAccountApiResponse(
        String operation,
        int bankHttpStatus,
        JsonNode bankResponse
) {
}
