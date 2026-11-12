package com.company.payment.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Nationalized;
import org.hibernate.type.SqlTypes;

/** Our own (debit) account used to pay from. Plaintext is used only in local development; production must protect it. */
@Entity
@Table(name = "c3_payer")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class Payer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payer_id") @ToString.Include
    private Long payerId;

    @Nationalized @Column(name = "payer_bank_name", nullable = false, length = 150)
    private String payerBankName;

    @Nationalized @Column(name = "payer_branch", length = 150)
    private String payerBranch;

    @Nationalized @Column(name = "payer_name", length = 150)
    private String payerName;

    @Column(name = "acc_num_plain", length = 18)
    private String accNumPlain;

    @Column(name = "acc_num_masked", length = 30) @ToString.Include
    private String accNumMasked;

    @Column(name = "ifsc_code", length = 11)
    private String ifscCode;

    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Nationalized @Column(name = "adapter_prvdr", nullable = false, length = 150)
    private String adapterPrvdr;

    @Column(name = "active_status_cdid", nullable = false)
    private boolean activeStatusCdid;

    @Column(name = "created_ts", nullable = false)
    private Instant createdTs;

    @Column(name = "updated_ts")
    private Instant updatedTs;
}
