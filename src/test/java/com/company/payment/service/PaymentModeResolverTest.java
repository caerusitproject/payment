package com.company.payment.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import com.company.payment.domain.entity.PaymentMode;
import com.company.payment.domain.repository.PaymentModeRepository;
import com.company.payment.exception.NoEligiblePaymentModeException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PaymentModeResolverTest {

    private static final BigDecimal MAX = new BigDecimal("9999999999999.99");
    private PaymentModeRepository repo;
    private PaymentModeResolver resolver;

    private static PaymentMode mode(long id, String code, String min, boolean active) {
        return PaymentMode.builder().pymntModeId(id).modeCode(code).modeName(code).minAmount(new BigDecimal(min))
                .maxAmount(MAX).activeStatusCdid(active).createdTs(Instant.now()).build();
    }

    @BeforeEach void setUp() {
        repo = Mockito.mock(PaymentModeRepository.class);
        when(repo.findByActiveStatusCdidTrue()).thenReturn(List.of(
                mode(1, "NEFT", "1.00", true), mode(2, "RTGS", "200000.00", true), mode(3, "FT", "1.00", true)));
        resolver = new PaymentModeResolver(repo);
    }

    @Test void smallAmountToOtherBankIsNeft() {
        assertEquals("NEFT", resolver.resolve(new BigDecimal("50000.00"), "HDFC0001234").getModeCode());
    }

    @Test void twoLakhAndAboveToOtherBankIsRtgs() {
        assertEquals("RTGS", resolver.resolve(new BigDecimal("200000.00"), "HDFC0001234").getModeCode());
        assertEquals("RTGS", resolver.resolve(new BigDecimal("999999.00"), "SBIN0000300").getModeCode());
    }

    @Test void justBelowTwoLakhIsNeft() {
        assertEquals("NEFT", resolver.resolve(new BigDecimal("199999.99"), "HDFC0001234").getModeCode());
    }

    @Test void iciciBeneficiaryIsAlwaysFt() {
        assertEquals("FT", resolver.resolve(new BigDecimal("50000.00"), "ICIC0000011").getModeCode());
        assertEquals("FT", resolver.resolve(new BigDecimal("500000.00"), "icic0000011").getModeCode());
    }

    @Test void inactiveRtgsFallsBackToNeft() {
        when(repo.findByActiveStatusCdidTrue()).thenReturn(List.of(mode(1, "NEFT", "1.00", true), mode(3, "FT", "1.00", true)));
        assertEquals("NEFT", resolver.resolve(new BigDecimal("500000.00"), "HDFC0001234").getModeCode());
    }

    @Test void noEligibleModeFails() {
        when(repo.findByActiveStatusCdidTrue()).thenReturn(List.of());
        assertThrows(NoEligiblePaymentModeException.class, () -> resolver.resolve(new BigDecimal("10.00"), "HDFC0001234"));
    }
}
