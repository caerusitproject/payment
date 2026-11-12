package com.company.payment.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/**
 * Payment request published by any module to the main payment queue (and accepted by the REST API).
 * {@code payment_mode_id} is optional and only informational: the mode is always calculated from the rules.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentRequestMessage(
        @JsonProperty("req_ref_name") @NotBlank @Size(max = 50) String reqRefName,
        @JsonProperty("req_ref_id") @NotNull Long reqRefId,
        @JsonProperty("ben_ref_entity_name") @NotBlank @Size(max = 50) String benRefEntityName,
        @JsonProperty("ben_ref_entity_id") @NotNull Long benRefEntityId,
        @JsonProperty("payment_mode_id") Long paymentModeId,
        @JsonProperty("txn_categ_cdid") @NotBlank @Size(max = 20) String txnCategCdid,
        @JsonProperty("ben_acct_id") @NotNull Long benAcctId,
        @JsonProperty("amount") @NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal amount) {

    /** Kafka message key: keeps one request's messages together. */
    public String key() { return reqRefName + ":" + reqRefId; }
}
