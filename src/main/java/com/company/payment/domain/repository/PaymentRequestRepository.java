package com.company.payment.domain.repository;

import com.company.payment.domain.entity.PaymentRequest;
import com.company.payment.domain.enums.ProcStatus;
import com.company.payment.domain.enums.QueueType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface PaymentRequestRepository extends JpaRepository<PaymentRequest, Long> {

    Optional<PaymentRequest> findByReqRefNameAndReqRefId(String reqRefName, Long reqRefId);

    boolean existsByReqRefNameAndReqRefId(String reqRefName, Long reqRefId);

    Optional<PaymentRequest> findFirstByProcStatusIn(Collection<ProcStatus> statuses);

    /** Oldest due request of a queue. */
    Optional<PaymentRequest> findFirstByQueueTypeAndProcStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscPymntReqIdAsc(
            QueueType queueType, ProcStatus procStatus, Instant now);


    /**
     * Claims one due pending inquiry by moving its due time forward before the bank call.
     *
     * Pending inquiry dispatch may publish duplicate wake-up events (Kafka is at-least-once).
     * This conditional update ensures that only one consumer can claim a particular due inquiry.
     * If the process crashes, the row becomes due again after the configured inquiry delay.
     *
     * @return 1 when this consumer claimed the inquiry; 0 for stale/duplicate events
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("""
        UPDATE PaymentRequest p
           SET p.nextAttemptAt = :leaseUntil
         WHERE p.pymntReqId = :paymentRequestId
           AND p.queueType = :pendingQueue
           AND p.procStatus = :pendingStatus
           AND p.nextAttemptAt IS NOT NULL
           AND p.nextAttemptAt <= :now
        """)
    int claimDuePendingInquiry(
            @Param("paymentRequestId") Long paymentRequestId,
            @Param("pendingQueue") QueueType pendingQueue,
            @Param("pendingStatus") ProcStatus pendingStatus,
            @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil
    );

    @Query("select p.queueType, p.procStatus, count(p) from PaymentRequest p group by p.queueType, p.procStatus")
    List<Object[]> countByQueueAndStatus();

    List<PaymentRequest> findAllByQueueTypeAndProcStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscPymntReqIdAsc(
            QueueType queueType,
            ProcStatus procStatus,
            Instant currentTime
    );
}
