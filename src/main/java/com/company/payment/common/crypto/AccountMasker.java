package com.company.payment.common.crypto;

/** Keeps only the last 4 characters of an account number: 123456789012 -> XXXXXXXX9012. */
public final class AccountMasker {
    private AccountMasker() {}

    public static String mask(String account) {
        if (account == null || account.isBlank()) return null;
        String a = account.trim();
        if (a.length() <= 4) return "X".repeat(a.length());
        return "X".repeat(a.length() - 4) + a.substring(a.length() - 4);
    }
}
