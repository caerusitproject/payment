package com.company.payment.beneficiary;

/**
 * Reads beneficiary account details from the system that owns them (the payment service keeps only
 * snapshots). Replace the dummy with a REST / Kafka lookup into that system.
 */
public interface BeneficiaryAccountProvider {
    BeneficiaryAccount fetch(Long benAcctId) throws BeneficiaryNotFoundException;
}
