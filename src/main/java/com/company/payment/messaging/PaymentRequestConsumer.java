package com.company.payment.messaging;

import com.company.payment.service.PaymentIntakeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Main payment queue (Kafka) -> pymnt_req. Single consumer thread keeps arrival order. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRequestConsumer {

    private final PaymentIntakeService intake;
    private final ObjectMapper mapper;

    @KafkaListener(topics = "${payment.topics.request}", concurrency = "1")
    public void onMessage(String payload) throws Exception {
        PaymentRequestMessage msg = mapper.readValue(payload, PaymentRequestMessage.class);
        boolean stored = intake.store(msg);
        log.info("Payment request {} {}", msg.key(), stored ? "stored" : "ignored (duplicate)");
    }
}
