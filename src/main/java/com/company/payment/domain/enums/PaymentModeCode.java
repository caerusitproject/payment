package com.company.payment.domain.enums;

/** Payment modes and the ICICI transaction type code each one maps to. */
public enum PaymentModeCode {
    NEFT("RGS"),
    RTGS("RTG"),
    IMPS("IFS"),   // not allowed for virtual payments
    FT("TPA");     // ICICI to ICICI

    private final String iciciTransactionType;

    PaymentModeCode(String iciciTransactionType) { this.iciciTransactionType = iciciTransactionType; }

    public String iciciTransactionType() { return iciciTransactionType; }
}
