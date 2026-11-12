package com.company.payment.domain.entity;

import com.company.payment.domain.enums.ProcStatus;
import com.company.payment.domain.enums.QueueType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row per original payment request. Retries re-use the row; each attempt is a row in {@link PaymentTxn}.
 * (req_ref_name, req_ref_id) identifies the calling module's record and makes intake idempotent.
 */
@Entity
@Table(name = "pymnt_req",
        uniqueConstraints = @UniqueConstraint(name = "uk_pymnt_req_ref", columnNames = {"req_ref_name", "req_ref_id"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor @ToString
public class PaymentRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pymnt_req_id")
    private Long pymntReqId;

    @Column(name = "req_ref_name", nullable = false, length = 50)
    private String reqRefName;

    @Column(name = "req_ref_id", nullable = false)
    private Long reqRefId;

    @Column(name = "ben_ref_entity_name", nullable = false, length = 50)
    private String benRefEntityName;

    @Column(name = "ben_ref_entity_id", nullable = false)
    private Long benRefEntityId;

    /** Always calculated by the payment-mode rules during processing; never taken from the caller. */
    @Column(name = "payment_mode_id")
    private Long paymentModeId;

    @Column(name = "txn_categ_cdid", nullable = false, length = 20)
    private String txnCategCdid;

    @Column(name = "ben_acct_id", nullable = false)
    private Long benAcctId;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Builder.Default
    @Column(name = "is_completed", nullable = false)
    private boolean completed = false;

    @Column(name = "initiated_ts", nullable = false)
    private Instant initiatedTs;

    @Column(name = "complete_ts")
    private Instant completeTs;

    // ---- queue / processing state ----
    @Enumerated(EnumType.STRING) @Column(name = "queue_type", nullable = false, length = 20)
    private QueueType queueType;

    @Enumerated(EnumType.STRING) @Column(name = "proc_status", nullable = false, length = 30)
    private ProcStatus procStatus;

    /** Retries already initiated (0 = original attempt only, max 3). */
    @Builder.Default
    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_reason", length = 1000)
    private String lastReason;
}
