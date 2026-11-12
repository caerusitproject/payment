package com.company.payment.domain.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.*;

/** NEFT / RTGS / FT with the amount band in which each mode may be used. */
@Entity
@Table(name = "c3_pymnt_mode")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor @ToString
public class PaymentMode {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pymnt_mode_id")
    private Long pymntModeId;

    @Column(name = "mode_code", nullable = false, length = 10)
    private String modeCode;

    @Column(name = "mode_name", nullable = false, length = 50)
    private String modeName;

    @Column(name = "min_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal minAmount;

    @Column(name = "max_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal maxAmount;

    @Column(name = "active_status_cdid", nullable = false)
    private boolean activeStatusCdid;

    @Column(name = "created_ts", nullable = false)
    private Instant createdTs;
}
