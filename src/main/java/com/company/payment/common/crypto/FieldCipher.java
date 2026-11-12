package com.company.payment.common.crypto;

import com.company.payment.config.CryptoProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM encryption for OTP values in the local worklist. Account numbers are deliberately stored as supplied in local mode.
 * Stored layout: 12-byte random IV || ciphertext || 16-byte auth tag.
 */
@Component
public class FieldCipher {
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public FieldCipher(CryptoProperties props) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(props.fieldKey());
        } catch (IllegalArgumentException e) {
            throw new CryptoException("crypto.field-key must be base64", e);
        }
        if (raw.length != 32) {
            throw new CryptoException("crypto.field-key must decode to 32 bytes (AES-256), got " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public byte[] encrypt(String plain) {
        if (plain == null) return null;
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Field encryption failed", e);
        }
    }

    public String decrypt(byte[] data) {
        if (data == null) return null;
        try {
            ByteBuffer buf = ByteBuffer.wrap(data);
            byte[] iv = new byte[IV_BYTES];
            buf.get(iv);
            byte[] ct = new byte[buf.remaining()];
            buf.get(ct);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new CryptoException("Field decryption failed (wrong key or corrupted data)", e);
        }
    }
}
