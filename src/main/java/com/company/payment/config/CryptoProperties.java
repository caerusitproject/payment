package com.company.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

/** Key material locations (prefix {@code crypto}). Real keys must live outside the repository. */
@ConfigurationProperties(prefix = "crypto")
public record CryptoProperties(
        String fieldKey,                 // base64, 32 bytes: AES-256-GCM key for data at rest
        Resource ourPrivateKey,          // PKCS#8 PEM, decrypts bank responses
        Resource ourCertificate,         // our public certificate (shared with ICICI)
        Resource iciciPublicCertificate) // ICICI's public certificate, encrypts requests
{}
