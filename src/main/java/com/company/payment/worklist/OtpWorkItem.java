package com.company.payment.worklist;

import java.math.BigDecimal;
import java.time.Instant;

public record OtpWorkItem(Long pymntReqId, Long pymntTxnId, String uniqueId, String title,
                          BigDecimal amount, Instant expiresTs) {}
