package com.company.payment.beneficiary;

public class BeneficiaryNotFoundException extends RuntimeException {
    public BeneficiaryNotFoundException(Long id) { super("Beneficiary account " + id + " not found in source system"); }
}
