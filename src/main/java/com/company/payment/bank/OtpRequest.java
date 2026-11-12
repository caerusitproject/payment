package com.company.payment.bank;

import java.math.BigDecimal;

public record OtpRequest(String uniqueId, BigDecimal amount) {}
