package com.company.payment.messaging;

import com.company.payment.config.PaymentProperties;
import com.company.payment.domain.entity.PaymentRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Mirrors queue movements to the retry / pending / manual Kafka topics for observability.
 * The database remains the source of truth for scheduling; a publish failure never breaks the payment flow.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueueEventPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final PaymentProperties props;
    private final ObjectMapper mapper;

    public void publish(PaymentRequest req) {
        String topic = switch (req.getQueueType()) {
            case RETRY_1 -> props.topics().retry1();
            case RETRY_2 -> props.topics().retry2();
            case RETRY_3 -> props.topics().retry3();
            case PENDING -> props.topics().pending();
            case MANUAL -> props.topics().manual();
            default -> null;
        };
        if (topic == null) return;
        try {
            QueueEvent ev = new QueueEvent(req.getPymntReqId(), req.getReqRefName(), req.getReqRefId(),
                    req.getQueueType(), req.getRetryCount(), req.getNextAttemptAt(), req.getLastReason());
            kafka.send(topic, req.getReqRefName() + ":" + req.getReqRefId(), mapper.writeValueAsString(ev));
        } catch (Exception e) {
            log.warn("Could not publish queue event for {}:{} - {}", req.getReqRefName(), req.getReqRefId(), e.toString());
        }
    }
}
