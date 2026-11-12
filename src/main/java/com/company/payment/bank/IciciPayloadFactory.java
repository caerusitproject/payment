package com.company.payment.bank;

import com.company.payment.config.IciciProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Builds plain JSON request bodies before RSA encryption. Field names follow the supplied ICICI workbook.
 * Keep the encrypted request within the bank gateway's RSA payload-size limit.
 */
@Component
@RequiredArgsConstructor
public class IciciPayloadFactory {

    private final IciciProperties props;
    private final ObjectMapper mapper;

    public String otp(OtpRequest r) {
        Map<String, Object> m = base(r.uniqueId());
        m.put("AMOUNT", money(r.amount()));
        return write(m);
    }

    public String transaction(SubmitRequest r) {
        Map<String, Object> m = base(r.uniqueId());
        m.put("DEBITACC", r.debitAccount());
        m.put("CREDITACC", r.creditAccount());
        // ICICI requires its own static IFSC for ICICI-to-ICICI FT (TPA) transfers.
        m.put("IFSC", "TPA".equalsIgnoreCase(r.transactionType()) ? "ICIC0000011" : r.ifsc());
        m.put("AMOUNT", money(r.amount()));
        m.put("CURRENCY", r.currency());
        m.put("TXNTYPE", r.transactionType());
        m.put("PAYEENAME", sanitize(r.payeeName(), 80));
        m.put("REMARKS", sanitize(r.remarks(), 32));
        m.put("CUSTOMERINDUCED", "Y");
        m.put("OTP", r.otp());
        return write(m);
    }

    public String inquiry(InquiryRequest r) {
        return write(base(r.uniqueId()));
    }

    public String balanceInquiry(BalanceInquiryRequest request) {
        Map<String, Object> m = accountBase();
        m.put("ACCOUNTNO", request.accountNo());
        return write(m);
    }

    public String accountStatement(AccountStatementRequest request) {
        Map<String, Object> m = accountBase();
        m.put("ACCOUNTNO", request.accountNo());
        m.put("FROMDATE", request.fromDate().format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy")));
        m.put("TODATE", request.toDate().format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy")));
        return write(m);
    }

    private Map<String, Object> accountBase() {
        IciciProperties.Credentials c = props.credentials();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("AGGRID", c.aggrId());
        m.put("CORPID", c.corpId());
        m.put("USERID", c.userId());
        m.put("URN", c.urn());
        return m;
    }

    private Map<String, Object> base(String uniqueId) {
        IciciProperties.Credentials c = props.credentials();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("AGGRID", c.aggrId());
        m.put("AGGRNAME", c.aggrName());
        m.put("CORPID", c.corpId());
        m.put("USERID", c.userId());
        m.put("URN", c.urn());
        m.put("UNIQUEID", uniqueId);
        return m;
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String sanitize(String value, int max) {
        if (value == null) return "";
        String safe = value.replaceAll("[^A-Za-z0-9 ]", " ").trim().replaceAll("\\s+", " ");
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    private String write(Map<String, Object> m) {
        try {
            return mapper.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise bank payload", e);
        }
    }
}
