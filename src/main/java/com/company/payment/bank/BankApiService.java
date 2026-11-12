package com.company.payment.bank;

import com.company.payment.common.crypto.CryptoException;
import com.company.payment.domain.entity.BankApiInteraction;
import com.company.payment.domain.enums.ApiOperation;
import com.company.payment.domain.repository.BankApiInteractionRepository;
import java.time.Instant;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Calls the bank and records every call in the interaction table. */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankApiService {

    private final IciciClient client;
    private final BankApiInteractionRepository interactions;

    public BankCallResult createOtp(OtpRequest request) {
        return call(ApiOperation.CREATE_OTP, request.uniqueId(), () -> client.createOtp(request));
    }

    public BankCallResult submit(SubmitRequest request) {
        return call(ApiOperation.PAYMENT_TXN, request.uniqueId(), () -> client.submitTransaction(request));
    }

    public BankCallResult inquire(InquiryRequest request) {
        return call(ApiOperation.STATUS_INQUIRY, request.uniqueId(), () -> client.inquire(request));
    }

    public BankCallResult accountStatement(AccountStatementRequest request) {
        return call(ApiOperation.ACCOUNT_STATEMENT, null, () -> client.accountStatement(request));
    }

    public BankCallResult balanceInquiry(BalanceInquiryRequest request) {
        return call(ApiOperation.BALANCE_FETCH, null, () -> client.balanceInquiry(request));
    }

    private BankCallResult call(ApiOperation op, String uniqueId, Supplier<BankResponse> action) {
        int attempt = uniqueId == null ? 1 : (int) interactions.countByUniqueIdAndApiOperation(uniqueId, op) + 1;
        Instant started = Instant.now();
        BankResponse response = null;
        boolean sent = true;
        String note = null;
        try {
            response = action.get();
        } catch (CryptoException e) {
            sent = false;
            note = "NOT_SENT: " + e.getMessage();
            log.error("{} not sent: {}", op, e.getMessage());
        } catch (Exception e) {
            note = "NO_RESPONSE: " + e;
            log.warn("{} call failed, outcome unknown: {}", op, e.toString());
        }
        String stored = response != null && response.body() != null ? response.body() : (note != null ? note : "");
        interactions.save(BankApiInteraction.builder()
                .apiOperation(op)
                .uniqueId(uniqueId)
                .attemptNum(attempt)
                .httpStatusCode(response == null ? null : response.httpStatus())
                .response(stored.length() > 4000 ? stored.substring(0, 4000) : stored)
                .initiatedTs(started)
                .completedTs(Instant.now())
                .build());
        return new BankCallResult(response, sent);
    }
}
