package com.company.payment.config;

import com.company.payment.common.crypto.AccountMasker;
import com.company.payment.domain.entity.Payer;
import com.company.payment.domain.entity.PaymentMode;
import com.company.payment.domain.repository.PayerRepository;
import com.company.payment.domain.repository.PaymentModeRepository;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Dev convenience: inserts the payment modes and one dummy payer when those tables are empty. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.seed-reference-data", havingValue = "true")
public class ReferenceDataSeeder implements ApplicationRunner {

    private static final BigDecimal MAX = new BigDecimal("9999999999999.99");

    private final PaymentModeRepository modes;
    private final PayerRepository payers;

    @Override
    public void run(ApplicationArguments args) {
        Instant now = Instant.now();
        if (modes.count() == 0) {
            modes.save(mode("NEFT", "National Electronic Funds Transfer", "1.00", MAX, now));
            modes.save(mode("RTGS", "Real Time Gross Settlement", "200000.00", MAX, now));
            modes.save(mode("FT", "Fund Transfer (ICICI to ICICI)", "1.00", MAX, now));
            log.info("Seeded payment modes");
        }
        if (payers.count() == 0) {
            String account = "000405002777";
            payers.save(Payer.builder()
                    .payerBankName("ICICI Bank")
                    .payerBranch("Corporate Banking")
                    .payerName("UT Org (dummy)")
                    .accNumPlain(account)
                    .accNumMasked(AccountMasker.mask(account))
                    .ifscCode("ICIC0000004")
                    .currency("INR")
                    .adapterPrvdr("ICICI_CIB")
                    .activeStatusCdid(true)
                    .createdTs(now)
                    .build());
            log.info("Seeded dummy payer");
        }
    }

    private static PaymentMode mode(String code, String name, String min, BigDecimal max, Instant now) {
        return PaymentMode.builder().modeCode(code).modeName(name).minAmount(new BigDecimal(min)).maxAmount(max)
                .activeStatusCdid(true).createdTs(now).build();
    }
}
