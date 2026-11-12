package com.company.payment.common.crypto;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AccountMaskerTest {
    @Test void keepsLastFour() { assertEquals("XXXXXXXX9012", AccountMasker.mask("123456789012")); }
    @Test void shortValuesFullyMasked() { assertEquals("XXX", AccountMasker.mask("123")); }
    @Test void nullAndBlank() { assertNull(AccountMasker.mask(null)); assertNull(AccountMasker.mask(" ")); }
}
