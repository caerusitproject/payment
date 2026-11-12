package com.company.payment.domain.entity;

import com.company.payment.domain.enums.WorklistItemStatus;
import com.company.payment.domain.enums.WorklistItemType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.*;

/**
 * Work item for the Accounts team (OTP entry) or for support (manual intervention).
 * Backing store of the stand-in worklist module; replace {@code WorklistGateway} to use a real one.
 */
@Entity
@Table(name = "c3_worklist_item")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@ToString(exclude = "otpEncrypted")
public class WorklistItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING) @Column(name = "item_type", nullable = false, length = 30)
    private WorklistItemType itemType;

    @Column(name = "pymnt_req_id", nullable = false)
    private Long pymntReqId;

    @Column(name = "pymnt_txn_id")
    private Long pymntTxnId;

    @Column(name = "unique_id", length = 40)
    private String uniqueId;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "detail", length = 1000)
    private String detail;

    @Column(name = "amount", precision = 18, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 20)
    private WorklistItemStatus status;

    /** AES-GCM encrypted OTP; present only between "completed" and "submitted to bank". */
    @Column(name = "otp_encrypted", length = 512)
    private byte[] otpEncrypted;

    @Column(name = "created_ts", nullable = false)
    private Instant createdTs;

    @Column(name = "expires_ts")
    private Instant expiresTs;

    @Column(name = "completed_ts")
    private Instant completedTs;
}
