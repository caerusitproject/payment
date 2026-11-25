
package com.company.payment.api;

import com.company.payment.bank.AccountStatementRequest;
import com.company.payment.bank.BalanceInquiryRequest;
import com.company.payment.dto.response.BankAccountApiResponse;
import com.company.payment.service.BankAccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/bank-accounts")
@RequiredArgsConstructor
public class BankAccountController {

    private final BankAccountService bankAccountService;

    @PostMapping("/statement")
    public ResponseEntity<BankAccountApiResponse> fetchStatement(
            @Valid @RequestBody AccountStatementRequest request) {

        return ResponseEntity.ok(
                bankAccountService.fetchStatement(request)
        );
    }

    @PostMapping("/balance")
    public ResponseEntity<BankAccountApiResponse> fetchBalance(
            @Valid @RequestBody BalanceInquiryRequest request) {

        return ResponseEntity.ok(
                bankAccountService.fetchBalance(request)
        );
    }
}
