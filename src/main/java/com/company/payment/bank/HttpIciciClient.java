package com.company.payment.bank;

import com.company.payment.common.crypto.CryptoException;
import com.company.payment.common.crypto.RsaCryptoService;
import com.company.payment.config.IciciProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Real ICICI CIB client (enable with {@code icici.client=http}).
 * POST, content-type text/plain, body = Base64(RSA(json)); headers apikey and x-forwarded-for.
 * Endpoint paths and the transaction / inquiry field names still have to be confirmed with ICICI.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "icici.client", havingValue = "http")
public class HttpIciciClient implements IciciClient {

    private final IciciProperties props;
    private final IciciPayloadFactory payloads;
    private final RsaCryptoService crypto;
    private final HttpClient http = HttpClient.newHttpClient();

    @Override
    public BankResponse createOtp(OtpRequest r) {
        return post(props.http().otpPath(), payloads.otp(r));
    }

    @Override
    public BankResponse submitTransaction(SubmitRequest r) {
        return post(props.http().txnPath(), payloads.transaction(r));
    }

    @Override
    public BankResponse inquire(InquiryRequest r) {
        return post(props.http().inquiryPath(), payloads.inquiry(r));
    }

    @Override
    public BankResponse accountStatement(AccountStatementRequest request) {
        return post(props.http().accountStatementPath(), payloads.accountStatement(request));
    }

    @Override
    public BankResponse balanceInquiry(BalanceInquiryRequest request) {
        return post(props.http().balanceInquiryPath(), payloads.balanceInquiry(request));
    }

    private BankResponse post(String path, String json) {
        String encrypted = crypto.encryptForBank(json);     // CryptoException here => nothing was sent
        IciciProperties.Http cfg = props.http();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(cfg.baseUrl() + path))
                .timeout(cfg.timeout())
                .header("accept", "*/*")
                .header("content-type", "text/plain");
        if (cfg.apiKey() != null && !cfg.apiKey().isBlank()) builder.header("apikey", cfg.apiKey());
        if (cfg.forwardedFor() != null && !cfg.forwardedFor().isBlank()) builder.header("x-forwarded-for", cfg.forwardedFor());
        HttpRequest request = builder.POST(BodyPublishers.ofString(encrypted)).build();
        try {
            HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
            return new BankResponse(response.statusCode(), decryptIfEncrypted(response.body()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);               // sent, outcome unknown
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling the bank", e);
        }
    }

    /** Gateway errors can come back unencrypted; keep the raw text in that case. */
    private String decryptIfEncrypted(String body) {
        if (body == null || body.isBlank()) return body;
        try {
            return crypto.decryptFromBank(body);
        } catch (CryptoException e) {
            log.warn("Bank response could not be decrypted; keeping raw body");
            return body;
        }
    }
}
