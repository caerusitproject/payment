package com.company.payment.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;
import org.hibernate.annotations.Nationalized;

/** Beneficiary bank details frozen when a payment attempt is processed. */
@Entity
@Table(name = "c3_benef_snapshot")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class BenefSnapshot {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "beni_snap_id") @ToString.Include
    private Long beniSnapId;

    /** Account id in the source system. */
    @Column(name = "ben_ref_id", nullable = false) @ToString.Include
    private Long benRefId;

    @Nationalized @Column(name = "ben_acct_hol_name", nullable = false, length = 150)
    private String benAcctHolName;

    @Column(name = "ben_acc_plain", length = 18)
    private String benAccPlain;

    @Column(name = "ben_acc_masked", nullable = false, length = 30) @ToString.Include
    private String benAccMasked;

    @Column(name = "ifsc_code", nullable = false, length = 11)
    private String ifscCode;

    @Nationalized @Column(name = "bank_name", length = 150)
    private String bankName;

    @Column(name = "txn_categ_cdid", length = 20)
    private String txnCategCdid;

    @Column(name = "created_ts", nullable = false)
    private Instant createdTs;

    @Column(name = "update_ts", nullable = false)
    private Instant updateTs;
}
