package com.company.payment.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@RequiredArgsConstructor
public class KafkaConfig {

    private final PaymentProperties props;

    @Value("${payment.kafka.replication-factor:1}")
    private int replicationFactor;

    /** One partition preserves ordering; production should use a replicated Kafka cluster. */
    private NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(1).replicas(replicationFactor).build();
    }

    @Bean NewTopic requestTopic() { return topic(props.topics().request()); }
    @Bean NewTopic requestDeadLetterTopic() { return topic(props.topics().request() + ".DLT"); }

    /** Poison messages go to payment.request.DLT after 3 quick retries instead of blocking the queue. */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), new FixedBackOff(1000L, 3));
    }
}
