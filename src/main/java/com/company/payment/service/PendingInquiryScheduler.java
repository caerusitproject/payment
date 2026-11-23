package com.company.payment.service;

import com.company.payment.domain.entity.PaymentRequest;
import com.company.payment.domain.enums.ProcStatus;
import com.company.payment.domain.enums.QueueType;
import com.company.payment.domain.repository.PaymentRequestRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Independent SQL-backed pending inquiry poller. It does not publish or consume Kafka events.
 * The workflow atomically claims each due inquiry before calling ICICI.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingInquiryScheduler {
    private final PaymentRequestRepository requests;
    private final PaymentWorkflowService workflow;

    @Scheduled(fixedDelayString = "${payment.pending.poll-interval-ms:5000}")
    public void pollDueInquiries() {
        requests.findFirstByQueueTypeAndProcStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscPymntReqIdAsc(
                QueueType.PENDING, ProcStatus.PENDING, Instant.now())
            .ifPresent(this::processOne);
    }

    private void processOne(PaymentRequest request) {
        try {
            workflow.processPendingEvent(request.getPymntReqId());
        } catch (Exception ex) {
            log.error("Pending inquiry failed for paymentRequestId={}", request.getPymntReqId(), ex);
        }
    }
}
