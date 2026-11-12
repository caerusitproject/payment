package com.company.payment.bank;

/** Balance inquiry request. Account number must be the registered ICICI account number. */
public record BalanceInquiryRequest(String accountNo) {}
