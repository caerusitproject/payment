package com.company.payment.bank;

/**
 * Port to ICICI CIB. Implementations encrypt the request, call the bank and return the DECRYPTED response.
 * They throw {@link com.company.payment.common.crypto.CryptoException} when nothing was sent (encryption
 * problem) and any other exception when the call went out but the outcome is unknown (timeout, I/O).
 */
public interface IciciClient {
    BankResponse createOtp(OtpRequest request);
    BankResponse submitTransaction(SubmitRequest request);
    BankResponse inquire(InquiryRequest request);
    BankResponse accountStatement(AccountStatementRequest request);
    BankResponse balanceInquiry(BalanceInquiryRequest request);
}
