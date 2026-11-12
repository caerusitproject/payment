package com.company.payment.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Prevents accidental live deployment while account-number encryption is not implemented. */
@Component
@Profile("prod")
public class ProductionProfileGuard implements ApplicationRunner {

    @Override
    public void run(ApplicationArguments args) {
        throw new IllegalStateException(
                "Production profile is a configuration scaffold only. Live processing is blocked: "
                + "account-number encryption at rest, bank-certified UAT, and production security sign-off "
                + "must be completed before enabling real ICICI payments.");
    }
}
