package com.company.payment.messaging;

import com.company.payment.service.PaymentWorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Independent pending-status pipeline. It never acquires the sequential OTP/payment slot. */
@Component
@RequiredArgsConstructor
public class PendingQueueConsumer {
    private final ObjectMapper mapper;
    private final PaymentWorkflowService workflow;

    @KafkaListener(topics = "${payment.topics.pending}", concurrency = "1")
    public void consume(String payload) throws Exception {
        QueueEvent event = mapper.readValue(payload, QueueEvent.class);
        workflow.processPendingEvent(event.pymntReqId());
    }
}
