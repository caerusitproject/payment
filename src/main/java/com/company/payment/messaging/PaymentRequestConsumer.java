package com.company.payment.messaging;

import com.company.payment.service.ListenerControl;
import com.company.payment.service.PaymentIntakeService;
import com.company.payment.service.PaymentWorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRequestConsumer {

    private final PaymentIntakeService intake;
    private final ListenerControl listenerControl;
    private final PaymentWorkflowService paymentWorkflow;
    private final ObjectMapper mapper;

    @KafkaListener(id = "paymentRequestListener", topics = "${payment.topics.request}", concurrency = "1")
    public void onMessage(String payload) throws Exception {

        PaymentRequestMessage msg =
                mapper.readValue(payload, PaymentRequestMessage.class);

        boolean stored = intake.store(msg);

        if (!stored) {
            log.info("Duplicate payment request ignored: {}", msg.key());
            return;
        }

        listenerControl.pause();

        paymentWorkflow.startNextPayment();
    }
}
