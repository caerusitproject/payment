package com.company.payment.common.crypto;

import static org.junit.jupiter.api.Assertions.*;

import com.company.payment.config.CryptoProperties;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class FieldCipherTest {

    private static FieldCipher cipher(byte fill) {
        byte[] key = new byte[32];
        Arrays.fill(key, fill);
        return new FieldCipher(new CryptoProperties(Base64.getEncoder().encodeToString(key), null, null, null));
    }

    @Test void roundTrip() {
        FieldCipher c = cipher((byte) 1);
        assertEquals("000405002777", c.decrypt(c.encrypt("000405002777")));
    }

    @Test void sameValueEncryptsDifferentlyEachTime() {
        FieldCipher c = cipher((byte) 1);
        assertFalse(Arrays.equals(c.encrypt("123456789012"), c.encrypt("123456789012")));
    }

    @Test void wrongKeyFails() {
        byte[] data = cipher((byte) 1).encrypt("secret");
        assertThrows(CryptoException.class, () -> cipher((byte) 2).decrypt(data));
    }

    @Test void tamperedDataFails() {
        FieldCipher c = cipher((byte) 1);
        byte[] data = c.encrypt("secret");
        data[data.length - 1] ^= 1;
        assertThrows(CryptoException.class, () -> c.decrypt(data));
    }

    @Test void keyMustBe32Bytes() {
        assertThrows(CryptoException.class,
                () -> new FieldCipher(new CryptoProperties(Base64.getEncoder().encodeToString(new byte[16]), null, null, null)));
    }

    @Test void nullPassesThrough() {
        FieldCipher c = cipher((byte) 1);
        assertNull(c.encrypt(null));
        assertNull(c.decrypt(null));
    }
}
