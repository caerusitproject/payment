package com.company.payment.service;

import com.company.payment.config.PaymentProperties;
import com.company.payment.domain.entity.PaymentRequest;
import com.company.payment.domain.enums.ProcStatus;
import com.company.payment.domain.enums.QueueType;
import com.company.payment.domain.repository.PaymentRequestRepository;
import com.company.payment.messaging.PaymentRequestMessage;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** Stores a request taken from the main queue in pymnt_req. Idempotent on (req_ref_name, req_ref_id). */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentIntakeService {

    private final PaymentRequestRepository requests;
    private final PaymentProperties props;

    /** @return true if stored, false if this request was already received */
    public boolean store(PaymentRequestMessage m) {
        if (requests.existsByReqRefNameAndReqRefId(m.reqRefName(), m.reqRefId())) return false;
        if (m.paymentModeId() != null) {
            log.debug("Ignoring caller-supplied payment_mode_id={} for {}; mode is calculated from rules", m.paymentModeId(), m.key());
        }
        Instant now = Instant.now();
        try {
            requests.save(PaymentRequest.builder()
                    .reqRefName(m.reqRefName())
                    .reqRefId(m.reqRefId())
                    .benRefEntityName(m.benRefEntityName())
                    .benRefEntityId(m.benRefEntityId())
                    .txnCategCdid(m.txnCategCdid())
                    .benAcctId(m.benAcctId())
                    .amount(m.amount())
                    .currency(props.defaultCurrency())
                    .initiatedTs(now)
                    .queueType(QueueType.NEW)
                    .procStatus(ProcStatus.QUEUED)
                    .nextAttemptAt(now)
                    .build());
            return true;
        } catch (DataIntegrityViolationException duplicate) {
            return false;
        }
    }
}
