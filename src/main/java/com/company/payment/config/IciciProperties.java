package com.company.payment.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** ICICI CIB connection settings (prefix {@code icici}). */
@ConfigurationProperties(prefix = "icici")
public record IciciProperties(String client, Credentials credentials, Http http) {

    public record Credentials(String aggrId, String aggrName, String corpId, String userId, String urn) {}

    public record Http(
            String baseUrl,
            String otpPath,
            String txnPath,
            String inquiryPath,
            String accountStatementPath,
            String balanceInquiryPath,
            String apiKey,
            String forwardedFor,
            Duration timeout
    ) {}
}
