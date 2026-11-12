package com.company.payment.bank;

import java.time.LocalDate;

/** Statement date range uses the ICICI DD-MM-YYYY wire format in the payload factory. */
public record AccountStatementRequest(String accountNo, LocalDate fromDate, LocalDate toDate) {}
