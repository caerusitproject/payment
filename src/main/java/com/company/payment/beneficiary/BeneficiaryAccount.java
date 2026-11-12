package com.company.payment.beneficiary;

/** Beneficiary bank details as held by the source system. */
public record BeneficiaryAccount(String accountNumber, String accountHolderName, String ifscCode,
                                 String bankName, String branchName, String txnCategCdid) {
    @Override public String toString() { return "BeneficiaryAccount{holder=" + accountHolderName + "}"; }
}
