package com.company.payment.config;

import com.company.payment.domain.enums.QueueType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Business settings of the payment service (prefix {@code payment}). */
@ConfigurationProperties(prefix = "payment")
public record PaymentProperties(
        Topics topics,
        Processor processor,
        Retry retry,
        Pending pending,
        Otp otp,
        String defaultCurrency,
        boolean seedReferenceData,
        Map<String, String> txnCategories) {

    public record Topics(String request) {}

    /** {@code priority}: queues in the order they are served (main queue first, then retry 1, 2, 3). */
    public record Processor(List<QueueType> priority) {}

    public record Retry(List<Duration> delays, int maxRetries) {}

    /**
     * Delay between TransactionInquiry calls for the same bank attempt.
     * Configured as PT15M (15 minutes); this is independent of scheduler polling.
     */
    public record Pending(Duration inquiryDelay, int maxInquiries) {}

    public record Otp(Duration expiry) {}


    public String categoryName(String txnCategCdid) {
        return txnCategories == null ? txnCategCdid : txnCategories.getOrDefault(txnCategCdid, txnCategCdid);
    }
}
