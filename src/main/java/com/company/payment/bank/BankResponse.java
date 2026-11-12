package com.company.payment.bank;

/** Decrypted response from the bank gateway. */
public record BankResponse(int httpStatus, String body) {}
