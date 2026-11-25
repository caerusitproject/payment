
package com.company.payment.service;

import com.company.payment.bank.AccountStatementRequest;
import com.company.payment.bank.BankApiService;
import com.company.payment.bank.BankCallResult;
import com.company.payment.bank.BankResponse;
import com.company.payment.bank.BalanceInquiryRequest;
import com.company.payment.dto.response.BankAccountApiResponse;
import com.company.payment.exception.BankApiException;
import com.company.payment.exception.BankCommunicationException;
import com.company.payment.exception.InvalidPaymentRequestException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BankAccountService {

    private final BankApiService bankApiService;
    private final ObjectMapper objectMapper;

    public BankAccountApiResponse fetchStatement(
            AccountStatementRequest request) {

        if (request.fromDate().isAfter(request.toDate())) {
            throw new InvalidPaymentRequestException(
                    "From date cannot be after To date");
        }

        BankCallResult result =
                bankApiService.accountStatement(request);

        return buildResponse(result, "ACCOUNT_STATEMENT");
    }

    public BankAccountApiResponse fetchBalance(
            BalanceInquiryRequest request) {

        BankCallResult result =
                bankApiService.balanceInquiry(request);

        return buildResponse(result, "BALANCE_FETCH");
    }

    private BankAccountApiResponse buildResponse(
            BankCallResult result,
            String operation) {

        if (result == null) {
            throw new BankCommunicationException(
                    "No result received from bank integration");
        }

        if (!result.sent()) {
            throw new BankApiException(
                    "Unable to send request to ICICI Bank");
        }

        BankResponse bankResponse = result.response();

        if (bankResponse == null) {
            throw new BankCommunicationException(
                    "No response received from ICICI Bank");
        }

        if (bankResponse.httpStatus() < 200
                || bankResponse.httpStatus() >= 300) {
            throw new BankApiException(
                    "ICICI Bank returned HTTP status "
                            + bankResponse.httpStatus());
        }

        if (bankResponse.body() == null
                || bankResponse.body().isBlank()) {
            throw new BankApiException(
                    "Empty response received from ICICI Bank");
        }

        try {
            JsonNode responseJson =
                    objectMapper.readTree(bankResponse.body());

            if (responseJson == null || !responseJson.isObject()) {
                throw new BankApiException(
                        "Invalid JSON response received from ICICI Bank");
            }

            return new BankAccountApiResponse(
                    operation,
                    bankResponse.httpStatus(),
                    responseJson
            );

        } catch (JsonProcessingException exception) {
            throw new BankApiException(
                    "Unable to parse ICICI Bank response",
                    exception
            );
        }
    }
}
