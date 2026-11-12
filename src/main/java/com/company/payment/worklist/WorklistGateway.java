package com.company.payment.worklist;

/** Port to the worklist module used by the Accounts team. */
public interface WorklistGateway {
    /** Creates the OTP-entry item for a transaction. */
    void createOtpItem(OtpWorkItem item);

    /** Current state of the transaction's OTP item (marks it EXPIRED when its window has passed). */
    OtpStatus otpStatus(Long pymntTxnId);

    /** Wipes the stored OTP once it has been sent to the bank. */
    void consumeOtp(Long pymntTxnId);

    /** Creates a manual-intervention item (idempotent per request). Nothing else happens to it automatically. */
    void createManualInterventionItem(ManualWorkItem item);
}
