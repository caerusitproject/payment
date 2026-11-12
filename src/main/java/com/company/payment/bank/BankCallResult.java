package com.company.payment.bank;

/**
 * Outcome of a bank call as seen by the workflow.
 * {@code sent == false} means the request never left our side (e.g. encryption failed), so the bank
 * cannot have processed it. {@code body} is null when the call went out but nothing usable came back.
 */
public record BankCallResult(BankResponse response, boolean sent) {
    public String body() { return response == null ? null : response.body(); }
}
