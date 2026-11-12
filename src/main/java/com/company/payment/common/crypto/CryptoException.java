package com.company.payment.common.crypto;

/** Any encryption / decryption / key-loading failure. */
public class CryptoException extends RuntimeException {
    public CryptoException(String message, Throwable cause) { super(message, cause); }
    public CryptoException(String message) { super(message); }
}
