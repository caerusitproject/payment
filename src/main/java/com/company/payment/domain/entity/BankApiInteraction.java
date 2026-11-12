package com.company.payment.domain.entity;

import com.company.payment.domain.enums.ApiOperation;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

/** Audit trail: one row per call made to the bank API. */
@Entity
@Table(name = "c3_bank_api_interaction")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor @ToString
public class BankApiInteraction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "api_interaction_id")
    private Long apiInteractionId;

    @Enumerated(EnumType.STRING) @Column(name = "api_operation", nullable = false, length = 30)
    private ApiOperation apiOperation;

    /** Only set for PAYMENT_TXN and STATUS_INQUIRY. */
    @Column(name = "unique_id", length = 40)
    private String uniqueId;

    /** How many times this operation has been hit for the same unique id (1, 2, 3 ...). */
    @Column(name = "attempt_num", nullable = false)
    private int attemptNum;

    @Column(name = "http_status_code")
    private Integer httpStatusCode;

    /** Decrypted bank response body, or a short note when nothing came back. */
    @Column(name = "response", nullable = false, length = 4000)
    private String response;

    @Column(name = "initiated_ts", nullable = false)
    private Instant initiatedTs;

    @Column(name = "completed_ts")
    private Instant completedTs;
}
