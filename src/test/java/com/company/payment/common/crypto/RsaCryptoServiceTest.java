package com.company.payment.common.crypto;

import static org.junit.jupiter.api.Assertions.*;

import com.company.payment.config.CryptoProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** Uses the explicitly non-production key pair bundled for local dummy testing. */
class RsaCryptoServiceTest {

    private final RsaCryptoService crypto = new RsaCryptoService(new CryptoProperties(
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            new ClassPathResource("certs/dev/dummy-our-private-key.pem"),
            new ClassPathResource("certs/dev/dummy-our-certificate.pem"),
            new ClassPathResource("certs/dev/dummy-bank-public-cert.pem")));

    @Test void responseEncryptedForUsRoundTrips() {
        String json = "{\"RESPONSE\":\"SUCCESS\",\"STATUS\":\"SUCCESS\"}";
        assertEquals(json, crypto.decryptFromBank(crypto.encryptForSelf(json)));
    }

    @Test void requestForDummyBankIsBase64AndCannotBeDecryptedWithOurPrivateKey() {
        String encrypted = crypto.encryptForBank("{\"UNIQUEID\":\"X\"}");
        assertEquals(684, encrypted.length()); // RSA-4096 ciphertext is 512 bytes, Base64 encoded.
        assertThrows(CryptoException.class, () -> crypto.decryptFromBank(encrypted));
    }

    @Test void oversizedPayloadIsRejectedBeforeSending() {
        assertThrows(CryptoException.class, () -> crypto.encryptForBank("x".repeat(502)));
        assertDoesNotThrow(() -> crypto.encryptForBank("x".repeat(501)));
    }
}
