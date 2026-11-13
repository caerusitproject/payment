package com.company.payment.messaging;

import com.company.payment.domain.enums.QueueType;
import com.company.payment.service.PaymentExecutionLock;
import com.company.payment.service.PaymentWorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consumes retry queue events. SQL Server application lock guarantees one payment/OTP flow across pods. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryQueueConsumer {
    private final ObjectMapper mapper;
    private final PaymentWorkflowService workflow;
    private final PaymentExecutionLock executionLock;

    @KafkaListener(topics = {
            "${payment.topics.retry1}",
            "${payment.topics.retry2}",
            "${payment.topics.retry3}"
    }, concurrency = "1")
    public void consume(String payload) throws Exception {
        QueueEvent event = mapper.readValue(payload, QueueEvent.class);
        if (event.queueType() == QueueType.RETRY_1
                || event.queueType() == QueueType.RETRY_2
                || event.queueType() == QueueType.RETRY_3) {
            executionLock.executeIfAcquired(
                    () -> workflow.processRetryEvent(event.pymntReqId(), event.queueType()));
        }
    }
}
