package com.company.payment.service;

import com.company.payment.config.PaymentProperties;
import com.company.payment.domain.enums.QueueType;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Three retry queues (15 min, 30 min, 2 h by default); after the third retry fails: manual intervention. */
@Component
@RequiredArgsConstructor
public class RetryPolicy {

    public record Step(QueueType queue, Duration delay) {}

    private final PaymentProperties props;

    /** @param retriesUsed retries already initiated (0 = only the original attempt has run) */
    public Optional<Step> next(int retriesUsed) {
        int max = Math.min(3, Math.min(props.retry().maxRetries(), props.retry().delays().size()));
        if (retriesUsed >= max) return Optional.empty();
        return Optional.of(new Step(QueueType.valueOf("RETRY_" + (retriesUsed + 1)), props.retry().delays().get(retriesUsed)));
    }

    /** RETRY_1 -> 1, RETRY_2 -> 2, RETRY_3 -> 3, anything else -> 0. */
    public static int retryNumber(QueueType queue) {
        return switch (queue) {
            case RETRY_1 -> 1;
            case RETRY_2 -> 2;
            case RETRY_3 -> 3;
            default -> 0;
        };
    }
}
