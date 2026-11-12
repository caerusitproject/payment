package com.company.payment.common.crypto;

import com.company.payment.config.CryptoProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import javax.crypto.Cipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Wire encryption for the ICICI client. Local dummy mode uses generated test certificates; production must replace them with bank-approved material:
 * <pre>
 *   request  = Base64( RSA/ECB/PKCS1Padding( json ) )   using ICICI's public certificate
 *   response = RSA decrypt( Base64Decode( body ) )      using OUR private key
 * </pre>
 * RSA/PKCS1 can only encrypt (keyBytes - 11) bytes, i.e. 501 bytes for a 4096-bit key.
 */
@Slf4j
@Component
public class RsaCryptoService {
    private static final String TRANSFORMATION = "RSA/ECB/PKCS1Padding";

    private final PrivateKey ourPrivateKey;
    private final X509Certificate ourCertificate;
    private final X509Certificate iciciCertificate;

    public RsaCryptoService(CryptoProperties props) {
        this.ourPrivateKey = PemKeyLoader.loadPrivateKey(props.ourPrivateKey());
        this.ourCertificate = PemKeyLoader.loadCertificate(props.ourCertificate());
        this.iciciCertificate = PemKeyLoader.loadCertificate(props.iciciPublicCertificate());
        assertValid("our certificate", ourCertificate);
        assertValid("ICICI certificate", iciciCertificate);
    }

    /** Encrypts a request payload for ICICI. */
    public String encryptForBank(String json) {
        return encrypt(json, iciciCertificate.getPublicKey());
    }

    /** Decrypts a response payload received from ICICI. */
    public String decryptFromBank(String base64Payload) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, ourPrivateKey);
            return new String(cipher.doFinal(Base64.getDecoder().decode(base64Payload.trim())), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new CryptoException("Cannot decrypt bank payload", e);
        }
    }

    /** Encrypts with OUR public certificate. Only the dummy bank uses this, to simulate ICICI's encrypted reply. */
    public String encryptForSelf(String json) {
        return encrypt(json, ourCertificate.getPublicKey());
    }

    private static String encrypt(String json, PublicKey key) {
        try {
            byte[] plain = json.getBytes(StandardCharsets.UTF_8);
            int max = (rsaKeyBytes(key)) - 11;
            if (plain.length > max) {
                throw new CryptoException("Payload is " + plain.length + " bytes; RSA/PKCS1 limit is " + max);
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return Base64.getEncoder().encodeToString(cipher.doFinal(plain));
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Cannot encrypt payload", e);
        }
    }

    private static int rsaKeyBytes(PublicKey key) {
        return ((java.security.interfaces.RSAPublicKey) key).getModulus().bitLength() / 8;
    }

    private static void assertValid(String name, X509Certificate cert) {
        try {
            cert.checkValidity();
            log.info("{} loaded: subject='{}', valid until {}", name, cert.getSubjectX500Principal(), cert.getNotAfter());
        } catch (CertificateException e) {
            throw new CryptoException(name + " is expired or not yet valid", e);
        }
    }
}
