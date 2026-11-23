package com.company.payment.domain.entity;

import com.company.payment.domain.enums.TxnStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One bank attempt for a payment request. Every (re)initiation creates a new row with a new unique id. */
@Entity
@Table(name = "c3_pymnt_txn",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_pymnt_txn_uid", columnNames = "unique_id"),
                @UniqueConstraint(name = "uk_pymnt_txn_req_attempt", columnNames = {"pymnt_req_id", "attempt_num"})
        })
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor @ToString
public class PaymentTxn {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pymnt_txn_id")
    private Long pymntTxnId;

    @Column(name = "pymnt_req_id", nullable = false)
    private Long pymntReqId;

    @Column(name = "payer_id", nullable = false)
    private Long payerId;

    /** Id of the beneficiary account in the source system. */
    @Column(name = "beneficiary_id", nullable = false)
    private Long beneficiaryId;

    /** Beneficiary snapshot used for this attempt. */
    @Column(name = "beni_snap_id")
    private Long beniSnapId;

    @Column(name = "payment_mode_id", nullable = false)
    private Long paymentModeId;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "remarks", length = 100)
    private String remarks;

    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** 1 = original attempt, 2 = first retry, ... */
    @Column(name = "attempt_num", nullable = false)
    private int attemptNum;

    /** ICICI transaction type: RTG (RTGS), RGS (NEFT), IFS (IMPS), TPA (ICICI to ICICI). */
    @Column(name = "transaction_type", nullable = false, length = 3)
    private String transactionType;

    @Column(name = "unique_id", nullable = false, length = 40)
    private String uniqueId;

    /** Filled from the transaction API response when it is SUCCESS. */
    @Column(name = "urn", length = 50)
    private String urn;

    @Enumerated(EnumType.STRING) @Column(name = "txn_status", nullable = false, length = 30)
    private TxnStatus txnStatus;

    /** SUCCESS / FAILURE as reported by the bank (UNKNOWN when nothing usable came back). */
    @Column(name = "response", nullable = false, length = 30)
    private String response;

    @Column(name = "resp_code", length = 30)
    private String respCode;

    @Column(name = "error_code", length = 30)
    private String errorCode;

    @Column(name = "failure_message", length = 1000)
    private String failureMessage;

    @Column(name = "initiated_ts", nullable = false)
    private Instant initiatedTs;

    @Column(name = "updated_ts")
    private Instant updatedTs;

    /** External Worklist task associated with this specific payment attempt. */
    @Column(name = "wkl_task_id")
    private Long wklTaskId;

    @Column(name = "wkl_task_created_ts")
    private LocalDateTime wklTaskCreatedTs;

    @Column(name = "otp_task_expires_ts")
    private LocalDateTime otpTaskExpiresTs;

    /** External Worklist task created for manual payment investigation. */
    @Column(name = "manual_wkl_task_id")
    private Long manualWklTaskId;

    @Column(name = "manual_wkl_task_created_ts")
    private LocalDateTime manualWklTaskCreatedTs;
}
