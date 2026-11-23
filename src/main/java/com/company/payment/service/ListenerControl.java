package com.company.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

/** Controls only the NEW payment listener; retry and pending listeners remain independent. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ListenerControl {
    private static final String LISTENER_ID = "paymentRequestListener";
    private final KafkaListenerEndpointRegistry registry;

    public void pause() {
        MessageListenerContainer container = registry.getListenerContainer(LISTENER_ID);
        if (container != null && !container.isPauseRequested()) {
            container.pause();
            log.info("NEW payment Kafka listener paused");
        }
    }

    public void resume() {
        MessageListenerContainer container = registry.getListenerContainer(LISTENER_ID);
        if (container != null && container.isPauseRequested()) {
            container.resume();
            log.info("NEW payment Kafka listener resumed");
        }
    }
}
