package com.company.payment.exception;

import com.company.payment.beneficiary.BeneficiaryNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({
            PaymentRequestNotFoundException.class,
            PaymentTransactionNotFoundException.class,
            BeneficiaryNotFoundException.class
    })
    public ResponseEntity<Map<String, Object>> handleNotFound(
            RuntimeException exception,
            HttpServletRequest request) {
        return buildResponse(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler({
            InvalidPaymentRequestException.class,
            InvalidBeneficiaryException.class,
            InvalidOtpException.class,
            OtpExpiredException.class
    })
    public ResponseEntity<Map<String, Object>> handleBadRequest(
            RuntimeException exception,
            HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
    }

    @ExceptionHandler(NoEligiblePaymentModeException.class)
    public ResponseEntity<Map<String, Object>> handleNoEligiblePaymentMode(
            NoEligiblePaymentModeException exception,
            HttpServletRequest request) {
        return buildResponse(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage(), request);
    }

    @ExceptionHandler({
            OtpAlreadyConsumedException.class,
            PaymentAlreadyProcessedException.class
    })
    public ResponseEntity<Map<String, Object>> handleConflict(
            RuntimeException exception,
            HttpServletRequest request) {
        return buildResponse(HttpStatus.CONFLICT, exception.getMessage(), request);
    }

    @ExceptionHandler(BankApiException.class)
    public ResponseEntity<Map<String, Object>> handleBankApi(
            BankApiException exception,
            HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_GATEWAY, exception.getMessage(), request);
    }

    @ExceptionHandler({
            BankCommunicationException.class,
            PaymentExecutionLockException.class
    })
    public ResponseEntity<Map<String, Object>> handleBankCommunication(
            RuntimeException exception,
            HttpServletRequest request) {
        return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), request);
    }

    @ExceptionHandler({
            PaymentProcessingException.class,
            Exception.class
    })
    public ResponseEntity<Map<String, Object>> handleInternalError(
            Exception exception,
            HttpServletRequest request) {
        // Log server-side details for diagnosis, but never return stack traces,
        // bank payloads, OTPs, account numbers or secret material to the caller.
        log.error("Unhandled payment API exception on {} {}", request.getMethod(),
                request.getRequestURI(), exception);
        return buildResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred while processing the payment.",
                request
        );
    }

    private ResponseEntity<Map<String, Object>> buildResponse(
            HttpStatus status,
            String message,
            HttpServletRequest request) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("path", request.getRequestURI());

        return ResponseEntity.status(status).body(body);
    }
}
