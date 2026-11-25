package com.company.payment.bank;

import com.company.payment.common.crypto.RsaCryptoService;
import com.company.payment.config.DummyIciciProperties;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stand-in for ICICI CIB. It still exercises the real crypto path:
 * the request is RSA-encrypted with the (dummy) ICICI certificate, and the reply is encrypted with OUR
 * certificate and decrypted again with OUR private key, exactly as the real gateway round trip would be.
 *
 * Behaviour is chosen per beneficiary account id via {@code icici.dummy.scenario-by-ben-acct-id}.
 * Scenarios: SUCCESS, TIMEOUT, TIMEOUT_NOT_PROCESSED, PENDING, BENE_TIMEOUT, INSUFFICIENT, CLOSED_ACCOUNT,
 * INVALID_IFSC, HOST_DOWN. Append _ONCE to fail only the first attempt. OTP 000000 is always "wrong OTP".
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "icici.client", havingValue = "dummy", matchIfMissing = true)
public class DummyIciciClient implements IciciClient {

    private static final String NOT_FOUND_INQUIRY =
            "{\"STATUS\":\"UNKNOWN\",\"MESSAGE\":\"Invalid Inquiry Req ID or Unique ID. Please enter a valid input to proceed.\",\"RESPONSE\":\"SUCCESS\"}";

    private static final class Outcome {
        final AtomicInteger pendingPolls;
        Outcome(int polls) { this.pendingPolls = new AtomicInteger(polls); }
    }

    private final IciciPayloadFactory payloads;
    private final RsaCryptoService crypto;
    private final DummyIciciProperties props;

    private final Map<String, Outcome> processed = new ConcurrentHashMap<>();
    private final Map<String, Integer> attemptsSeen = new ConcurrentHashMap<>();

    @Override
    public BankResponse createOtp(OtpRequest r) {
        log.info("[DUMMY-ICICI] create OTP");
        return roundTrip(payloads.otp(r),
                () -> "{\"RESPONSE\":\"SUCCESS\",\"RESPONSECODE\":\"0\",\"MESSAGE\":\"OTP sent to registered mobile\"}");
    }

    @Override
    public BankResponse submitTransaction(SubmitRequest r) {
        log.info("[DUMMY-ICICI] submit transaction uniqueId={}, amount={}", r.uniqueId(), r.amount());
        return roundTrip(payloads.transaction(r), () -> simulateSubmit(r));
    }

    @Override
    public BankResponse inquire(InquiryRequest r) {
        log.info("[DUMMY-ICICI] inquiry {}", r.uniqueId());
        return roundTrip(payloads.inquiry(r), () -> simulateInquiry(r.uniqueId()));
    }


    @Override
    public BankResponse accountStatement(
            AccountStatementRequest request) {

        return roundTrip(
                payloads.accountStatement(request),
                () -> """
                {
                  "CORP_ID": "LOCAL-DUMMY-CORP",
                  "USER_ID": "LOCAL-DUMMY-USER",
                  "AGGR_ID": "LOCAL-DUMMY-AGGR",
                  "URN": "LOCAL-DUMMY-URN",
                  "ACCOUNTNO": "%s",
                  "TXNDATE": "03-07-2015 12:00:00 AM",
                  "REMARKS": "Salary",
                  "AMOUNT": "0.00",
                  "BALANCE": "48964.97",
                  "VALUEDATE": "03-07-2015 12:00:00 AM",
                  "TYPE": "CR",
                  "TRANSACTIONID": "S26063006",
                  "RESPONSE": "SUCCESS"
                }
                """.formatted(request.accountNo())
        );
    }

    @Override
    public BankResponse balanceInquiry(
            BalanceInquiryRequest request) {

        return roundTrip(
                payloads.balanceInquiry(request),
                () -> """
                {
                  "AGGR_ID": "LOCAL-DUMMY-AGGR",
                  "CORP_ID": "LOCAL-DUMMY-CORP",
                  "USER_ID": "LOCAL-DUMMY-USER",
                  "URN": "LOCAL-DUMMY-URN",
                  "ACCOUNTNO": "%s",
                  "RESPONSE": "SUCCESS",
                  "DATE": "25-11-2026",
                  "EFFECTIVEBAL": 48964.970,
                  "CURRENCY": "INR"
                }
                """.formatted(request.accountNo())
        );
    }

    /** encrypt request for the bank -> (bank works) -> bank encrypts reply for us -> we decrypt. */
    private BankResponse roundTrip(String requestJson, Supplier<String> bank) {
        String encryptedRequest = crypto.encryptForBank(requestJson);
        log.debug("[DUMMY-ICICI] request encrypted ({} chars)", encryptedRequest.length());
        String encryptedReply = crypto.encryptForSelf(bank.get());
        return new BankResponse(200, crypto.decryptFromBank(encryptedReply));
    }

    private String simulateSubmit(SubmitRequest r) {
        if ("000000".equals(r.otp())) {
            return "{\"ErrorCode\":\"994006\",\"RESPONSE\":\"FAILURE\",\"MESSAGE\":\"OTP entered is wrong\"}";
        }
        String scenario = props.scenarioByBenAcctId() == null ? props.defaultScenario()
                : props.scenarioByBenAcctId().getOrDefault(String.valueOf(r.benAcctId()), props.defaultScenario());
        if (scenario.endsWith("_ONCE")) {
            scenario = scenario.substring(0, scenario.length() - 5);
            int n = attemptsSeen.merge(r.debitAccount() + "|" + r.creditAccount() + "|" + r.amount(), 1, Integer::sum);
            if (n > 1) scenario = "SUCCESS";
        }
        return switch (scenario) {
            case "TIMEOUT" -> {
                processed.put(r.uniqueId(), new Outcome(1));
                yield "{\"success\":false,\"response\":8012,\"message\":\"BACKEND_CONNECTION_TIMEOUT-Cannot connect to service\"}";
            }
            case "TIMEOUT_NOT_PROCESSED" ->
                    "{\"success\":false,\"response\":8012,\"message\":\"BACKEND_CONNECTION_TIMEOUT-Cannot connect to service\"}";
            case "PENDING" -> {
                processed.put(r.uniqueId(), new Outcome(2));
                yield "{\"RESPONSE\":\"SUCCESS\",\"STATUS\":\"PENDING\"}";
            }
            case "BENE_TIMEOUT" -> {
                processed.put(r.uniqueId(), new Outcome(1));
                yield "{\"RESPONSE\":\"Failure\",\"STATUS\":\"FAILURE\",\"MESSAGE\":\"Timed out at beneficiary bank\"}";
            }
            case "INSUFFICIENT" ->
                    "{\"RESPONSE\":\"Failure\",\"STATUS\":\"FAILURE\",\"MESSAGE\":\"Transaction with reference id 198326416 failed during processing, due to Insufficient funds\"}";
            case "CLOSED_ACCOUNT" -> "{\"RESPONSE\":\"Failure\",\"STATUS\":\"FAILURE\",\"MESSAGE\":\"Counterparty Account is a closed account\"}";
            case "INVALID_IFSC" -> "{\"RESPONSE\":\"Failure\",\"STATUS\":\"FAILURE\",\"MESSAGE\":\"Invalid IFSC code\"}";
            case "HOST_DOWN" -> "{\"RESPONSE\":\"Failure\",\"STATUS\":\"FAILURE\",\"MESSAGE\":\"Host not available\"}";
            default -> {
                processed.put(r.uniqueId(), new Outcome(0));
                yield "{\"RESPONSE\":\"SUCCESS\",\"STATUS\":\"SUCCESS\",\"URN\":\"UTR"
                        + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase() + "\"}";
            }
        };
    }

    private String simulateInquiry(String uniqueId) {
        Outcome o = processed.get(uniqueId);
        if (o == null) return NOT_FOUND_INQUIRY;
        if (o.pendingPolls.get() > 0) {
            o.pendingPolls.decrementAndGet();
            return "{\"RESPONSE\":\"SUCCESS\",\"STATUS\":\"PENDING\"}";
        }
        return "{\"RESPONSE\":\"SUCCESS\",\"STATUS\":\"SUCCESS\"}";
    }
}
