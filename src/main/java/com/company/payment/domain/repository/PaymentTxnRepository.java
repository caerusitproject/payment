package com.company.payment.domain.repository;

import com.company.payment.domain.entity.PaymentTxn;
import java.util.List;
import java.util.Optional;

import com.company.payment.domain.enums.TxnStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface PaymentTxnRepository extends JpaRepository<PaymentTxn, Long> {
    Optional<PaymentTxn> findByUniqueId(String uniqueId);
    Optional<PaymentTxn> findByWklTaskId(Long wklTaskId);
    List<PaymentTxn> findByPymntReqIdOrderByAttemptNumAsc(Long pymntReqId);
    Optional<PaymentTxn> findFirstByPymntReqIdOrderByPymntTxnIdDesc(Long pymntReqId);

    /**
     * Atomically changes the transaction status from OTP_REQUESTED
     * to SUBMITTED.
     *
     * Only one execution path can successfully claim the transaction.
     *
     * @param transactionId payment transaction ID
     * @param otpRequested expected current status
     * @param submitted new transaction status
     * @return 1 if successfully claimed, otherwise 0
     */
    @Modifying(
            clearAutomatically = true,
            flushAutomatically = true
    )
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("""
        UPDATE PaymentTxn t
           SET t.txnStatus = :submitted
         WHERE t.pymntTxnId = :transactionId
           AND t.txnStatus = :otpRequested
        """)
    int markSubmittedIfOtpRequested(
            @Param("transactionId") Long transactionId,
            @Param("otpRequested") TxnStatus otpRequested,
            @Param("submitted") TxnStatus submitted
    );

}
