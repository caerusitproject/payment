package com.company.payment.service;

import com.company.payment.domain.entity.PaymentRequest;
import com.company.payment.domain.enums.ProcStatus;
import com.company.payment.domain.enums.QueueType;
import com.company.payment.domain.repository.PaymentRequestRepository;
import com.company.payment.messaging.QueueEventPublisher;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes due database queue records to Kafka. Kafka consumers perform the actual work.
 * PostgreSQL remains the durable scheduling ledger; Kafka is the processing transport.
 *
 * <p>Events are wake-up signals and may be published more than once until the consumer
 * advances the database due time. Consumers must revalidate and atomically claim due work;
 * they must not trust an event as proof that the work is still eligible.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueueDispatchScheduler {
    private final PaymentRequestRepository requests;
    private final QueueEventPublisher publisher;

    @Scheduled(fixedDelayString = "${payment.queue-dispatch.poll-interval-ms:5000}")
    public void dispatchDueQueues() {
        Instant now = Instant.now();
        dispatch(QueueType.RETRY_1, ProcStatus.RETRY_WAIT, now);
        dispatch(QueueType.RETRY_2, ProcStatus.RETRY_WAIT, now);
        dispatch(QueueType.RETRY_3, ProcStatus.RETRY_WAIT, now);
        dispatch(QueueType.PENDING, ProcStatus.PENDING, now);
    }

    /** Publishes the oldest due row; consumer-side conditional updates prevent duplicate processing. */
    private void dispatch(QueueType queue, ProcStatus status, Instant now) {
        Optional<PaymentRequest> oldestDue =
                //QueueDispatchScheduler periodically checks the database and finds payment requests whose nextAttemptAt time has already arrived or passed.
                requests.findFirstByQueueTypeAndProcStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscPymntReqIdAsc(
                        queue, status, now);
        oldestDue.ifPresent(publisher::publish);
    }
}
