package com.company.payment.common.crypto;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import org.springframework.core.io.Resource;

/** Loads PKCS#8 PEM private keys and X.509 PEM/DER certificates. */
public final class PemKeyLoader {
    private PemKeyLoader() {}

    public static PrivateKey loadPrivateKey(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            String pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII)
                    .replaceAll("-----BEGIN [A-Z ]*PRIVATE KEY-----", "")
                    .replaceAll("-----END [A-Z ]*PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(pem);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            throw new CryptoException("Cannot load private key from " + resource.getDescription()
                    + " (expected an unencrypted PKCS#8 PEM)", e);
        }
    }

    public static X509Certificate loadCertificate(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        } catch (IOException | GeneralSecurityException e) {
            throw new CryptoException("Cannot load certificate from " + resource.getDescription(), e);
        }
    }
}
