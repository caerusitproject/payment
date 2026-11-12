# Payment Service – Code Review and Change Log (v2.1)

Date: 12-Nov-2026  
Reviewed artifact: `payment-service-v4-tes.zip`

## 1. Pending inquiry interval

- Changed `payment.pending.inquiry-delay` to `PT15M` in both `application.yml` and `application-dev.yml`.
- Kept `payment.pending.max-inquiries` at `12`.
- Kept queue dispatcher polling separate at 5 seconds (base profile) / 5 seconds (dev queue dispatcher).
- Removed the unused `payment.execution.gap` configuration entry. No artificial execution-gap sleep is required.
- Retry timings were not changed: base profile remains 15 minutes, 30 minutes, and 2 hours. Dev retry timings remain accelerated.

## 2. Pending inquiry duplicate protection

- Added `PaymentRequestRepository.claimDuePendingInquiry(...)`, a conditional JPQL update.
- `PaymentWorkflowService.processPendingEvent(...)` now claims a due pending inquiry by advancing `nextAttemptAt` by the configured inquiry delay before calling the bank.
- Duplicate/stale Kafka wake-up events become no-ops when the conditional update returns zero.
- If the process stops during inquiry, the row becomes due again after the 15-minute lease. Recovery still uses the original `UNIQUEID` and calls TransactionInquiry only; it never calls Create.

## 3. OTP API and payment execution lock

- Added bean validation to `OtpSubmitRequest`: transaction ID is required and OTP must contain 4–10 digits.
- Added service-level OTP format validation as a second defensive check.
- The OTP REST endpoint now uses the same PostgreSQL advisory execution lock as the scheduler/retry processing. This prevents scheduler crash-recovery from racing an API-triggered submission.
- The existing atomic transaction status claim (`OTP_REQUESTED` -> `SUBMITTED`) remains in place as the final duplicate-submission guard.

## 4. Exception handling corrections

- Consolidated `NoEligiblePaymentModeException` into `com.company.payment.exception`; removed the duplicate service-package class.
- Removed the unused duplicate exception-package `BeneficiaryNotFoundException`; the handler now catches the actual beneficiary-package exception thrown by the provider.
- `latestTxn()` now throws `PaymentTransactionNotFoundException` with a message that correctly identifies the missing payment transaction for its request.
- `PaymentExecutionLock` now throws `PaymentExecutionLockException` on SQL lock failures.
- Added server-side logging for unexpected API exceptions while returning a generic 500 response.
- Ordered `ApiExceptionHandler` before the catch-all global advice so worklist `ApiException` and validation errors retain their intended status responses.
- OTP endpoint request validation errors are handled as HTTP 400; busy payment execution lock returns HTTP 503.

## 5. Certificates and bank client

The default and dev profiles use `DummyIciciClient` and generated local-only files:
- `certs/dev/dummy-our-private-key.pem`
- `certs/dev/dummy-our-certificate.pem`
- `certs/dev/dummy-bank-public-cert.pem`

These are not ICICI-issued UAT or production certificates. The UAT profile has been made explicitly dummy-mode as well; a sandbox base URL alone does not enable real HTTP calls. The production profile selects the HTTP client but `ProductionProfileGuard` blocks startup until production security, account encryption, ICICI certification and operational sign-off are completed.

## 6. Documentation

Updated:
- `README.md`
- `docs/Local Development and Production Readiness.md`
- `docs/Payment Service - Functional Document v2.docx`
- `docs/Payment Service - Technical and Coding Document v2.docx`

The functional and technical Word documents were updated to describe the 15-minute inquiry delay, the 5-second dispatcher poll, atomic pending inquiry claim, dummy certificate status and current environment behavior.

## 7. Verification status and remaining work

- Source-level review and configuration/documentation consistency checks were performed.
- Maven tests could not be executed in this environment because Maven is not installed (`mvn: command not found`). Run `mvn clean test` in the project environment before merging.
- The 15-minute inquiry interval differs from the earlier two-hour workbook-based default. Obtain ICICI confirmation before UAT sign-off, including any IMPS-specific rule.
- Production remains blocked. Replace dummy certificates with ICICI-issued UAT/production material and confirm the final packet contract before any real bank call.
