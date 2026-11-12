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
    @Bean NewTopic retry1Topic() { return topic(props.topics().retry1()); }
    @Bean NewTopic retry1DeadLetterTopic() { return topic(props.topics().retry1() + ".DLT"); }
    @Bean NewTopic retry2Topic() { return topic(props.topics().retry2()); }
    @Bean NewTopic retry2DeadLetterTopic() { return topic(props.topics().retry2() + ".DLT"); }
    @Bean NewTopic retry3Topic() { return topic(props.topics().retry3()); }
    @Bean NewTopic retry3DeadLetterTopic() { return topic(props.topics().retry3() + ".DLT"); }
    @Bean NewTopic pendingTopic() { return topic(props.topics().pending()); }
    @Bean NewTopic pendingDeadLetterTopic() { return topic(props.topics().pending() + ".DLT"); }
    @Bean NewTopic manualTopic() { return topic(props.topics().manual()); }

    /** Poison messages go to payment.request.DLT after 3 quick retries instead of blocking the queue. */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), new FixedBackOff(1000L, 3));
    }
}
