# Payment Service — Local Dummy Mode and Production Scope

Spring Boot 3.3.5 / Java 21 / PostgreSQL / Kafka. The default profile is `dev` and the default bank client is `dummy`.
The application does **not** call ICICI when started with the default settings.

## 1. Local setup

Prerequisites: JDK 21, Maven 3.9+, Docker Desktop.

```bash
docker compose up -d
mvn clean test
mvn spring-boot:run
```

Docker Compose starts PostgreSQL (`localhost:5432`, database `paymentdb`, user `payment_user`, password `change-me`) and Kafka (`localhost:9092`). Flyway applies the schema and the `dev` profile seeds payment modes plus a dummy payer. The default Kafka setting is `localhost:9092`.

The app runs at `http://localhost:8080`. It uses:
- `DummyIciciClient` — simulated OTP, Create, and TransactionInquiry responses.
- `DummyBeneficiaryAccountProvider` — generated beneficiary details for positive beneficiary IDs.
- Test-only RSA certificates and private key under `src/main/resources/certs/dev/`.
- Plain account-number columns (`acc_num_plain`, `ben_acc_plain`) as explicitly requested for local development. The legacy encrypted columns are retained by the migration but are not written by the new local-mode code.
- AES-GCM remains enabled for OTP values in the Accounts worklist; the local AES key in `application.yml` is test-only.

## 2. Try the payment flow

Create a payment:

```bash
curl -X POST http://localhost:8080/api/payments \
  -H 'Content-Type: application/json' \
  -d '{"req_ref_name":"SALARY","req_ref_id":123,"ben_ref_entity_name":"C3_EMPLOYEE","ben_ref_entity_id":456,"txn_categ_cdid":"1024","ben_acct_id":789,"amount":50000.00}'
```

Check the OTP work item and submit a test OTP:

```bash
curl http://localhost:8080/api/worklist/otp/current
curl -X POST http://localhost:8080/api/worklist/otp/1/complete \
  -H 'Content-Type: application/json' -d '{"otp":"123456"}'
```

Check payment state and queues:

```bash
curl http://localhost:8080/api/payments/SALARY/123
curl http://localhost:8080/api/payments/queues
curl http://localhost:8080/api/worklist/manual
```

OTP `000000` simulates an incorrect OTP. Any other 4–10 digit OTP is accepted by the dummy client.

## 3. Dummy bank scenarios

Configure scenarios in `icici.dummy.scenario-by-ben-acct-id` in `application.yml`:

| Beneficiary ID | Simulated result |
|---:|---|
| 901 | `HOST_DOWN_ONCE` — first attempt fails; next retry succeeds |
| 902 | `HOST_DOWN` — repeated failure, then manual intervention after retry limit |
| 903 | `INSUFFICIENT` — confirmed failure |
| 904 | `TIMEOUT` — bank processed it; inquiry later returns success |
| 905 | `TIMEOUT_NOT_PROCESSED` — inquiry returns unknown; safe retry path is exercised |
| 906 | `PENDING` — inquiry returns pending twice, then success |
| 907 | `BENE_TIMEOUT` — beneficiary-bank timeout / pending inquiry path |
| 908 | `CLOSED_ACCOUNT` — confirmed closed-account failure |
| 909 | `INVALID_IFSC` — confirmed invalid-IFSC failure |
| 800–899 | ICICI beneficiary IFSC; FT mode is selected |
| Other positive IDs | HDFC beneficiary IFSC; NEFT/RTGS selection is exercised |

`icici.dummy.default-scenario` is `SUCCESS`. Dev retry timers and OTP expiry are shortened in `application-dev.yml`; the pending inquiry interval remains 15 minutes in every profile, as requested. Production retry/inquiry timings remain in the base configuration.

## 4. Local cryptographic material

`certs/dev/dummy-our-private-key.pem` and `dummy-our-certificate.pem` are a generated local-only key pair. `dummy-bank-public-cert.pem` is a generated test bank certificate. They are not issued by ICICI and must never be used against UAT or production. The dummy client uses the local certificate pair to exercise RSA encryption/decryption without needing ICICI material.

Do not send real customer or beneficiary data through this local configuration. Account numbers are intentionally stored without application-level encryption in this development build. OTP values remain encrypted at rest using a test-only AES key.

## 5. Production scope — not enabled yet

A production profile scaffold is provided in `application-prod.yml`, but this project is **not approved for live payment processing yet**. Before activating real ICICI calls, complete and verify all of the following:

1. Obtain ICICI-issued UAT/production certificates, key-exchange instructions, endpoint URLs (including bank-assigned port), API key/header requirements, AGGR/CORP/USER/URN values, and exact request/response packet specifications.
2. Replace all `certs/dev/*` references with secret-manager-mounted, bank-approved certificate/key locations. Never package a private key in the application JAR/container image.
3. Implement and test production-grade account-number encryption at rest and key rotation. The current code stores account numbers as plaintext in `*_plain` columns; this is for local development only.
4. Replace the hard-coded local AES key with a KMS/HSM/secret-manager-managed key. Keep OTP encryption and ensure OTPs are never logged.
5. Confirm API headers (`apikey`, `x-forwarded-for`) with ICICI. The HTTP client adds them only when configured; omitting them is not evidence that the bank does not require them.
6. Validate ICICI's response `STATUS` precedence, retryability codes, 15-minute pending inquiry delay/limits (including payment-mode differences), transaction mode mapping, and inquiry field requirements with the bank.
7. Add production authentication/authorization, TLS, network allow-listing, distributed locking, durable outbox/inbox delivery, operational alerting, audit retention, disaster recovery, and reconciliation procedures.
8. Run end-to-end UAT with ICICI-issued credentials and signed-off test cases before enabling `icici.client=http`.

To start with the production profile, set `SPRING_PROFILES_ACTIVE=prod` and supply all required `ICICI_*`, `CRYPTO_*`, and database/Kafka secrets. Do not do this until the above readiness items are completed. The production configuration is a deployment scaffold, not a claim of bank certification. The default/dev runtime currently uses DummyIciciClient and local dummy certificates; the UAT profile also remains on the dummy client unless explicitly configured otherwise.

## 6. Important workflow rules retained

- `STATUS` is the primary transaction outcome field; `RESPONSE` is secondary.
- A `PENDING` payment is resolved through TransactionInquiry using the original `UNIQUEID`; it is not submitted again while pending.
- Retry attempts use a new transaction row and `UNIQUEID`; maximum three retries, then manual intervention.
- OTP work is sequential: one active payment/OTP work item at a time.
- The dummy bank simulates bank behavior only; it does not prove ICICI packet compatibility or actual settlement.


## 7. Pending inquiry timing

The pending inquiry delay is `PT15M` (15 minutes) in both the base and dev profiles. The queue dispatcher polls every five seconds, but that is only a database scheduling check; it does not call ICICI every five seconds. Due Kafka events are revalidated and atomically claimed by the pending consumer. If ICICI remains PENDING, the next inquiry is scheduled 15 minutes later using the original transaction `UNIQUEID`. The maximum is 12 inquiries, after which the payment is routed to manual intervention. Confirm the 15-minute interval with ICICI before UAT sign-off because it differs from the earlier two-hour workbook-based default.
