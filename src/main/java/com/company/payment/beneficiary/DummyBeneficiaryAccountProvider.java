package com.company.payment.beneficiary;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Generates a beneficiary for any positive id. Ids 800-899 get an ICICI IFSC (so the FT mode is chosen);
 * everything else gets an HDFC IFSC (NEFT / RTGS by amount). Ids <= 0 are "not found".
 */
@Component
@ConditionalOnProperty(name = "beneficiary.provider", havingValue = "dummy", matchIfMissing = true)
public class DummyBeneficiaryAccountProvider implements BeneficiaryAccountProvider {

    @Override
    public BeneficiaryAccount fetch(Long id) {
        if (id == null || id <= 0) throw new BeneficiaryNotFoundException(id);
        boolean icici = id >= 800 && id < 900;
        return new BeneficiaryAccount(
                String.format("5%011d", id),
                "Dummy Beneficiary " + id,
                icici ? "ICIC0000011" : "HDFC0001234",
                icici ? "ICICI Bank" : "HDFC Bank",
                "Main Branch",
                icici ? "FT" : "NEFT");
    }
}
