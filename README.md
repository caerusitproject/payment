# Payment Service — SQL Server, Kafka and ICICI workflow

Spring Boot 3.3.5 / Java 21 / SQL Server / Kafka. The default bank client remains `dummy`;
the application does not call ICICI when started with the default settings.

## 1. Local setup

Prerequisites: JDK 21, Maven 3.9+, Docker Desktop.

Set a strong SQL Server SA password (SQL Server requires a complex password), then start dependencies:

```bash
# PowerShell
$env:DB_PASSWORD = "Your_Strong_Password123!"
docker compose up -d

# Create the database once (using SSMS or sqlcmd):
# CREATE DATABASE payments;

# Apply src/main/resources/src/main/resources/db/sqlserver/schema.sql to the payments database.
# For an existing database, apply src/main/resources/db/sqlserver/V3__worklist_task_correlation.sql instead.

$env:DB_URL = "jdbc:sqlserver://localhost:1433;databaseName=payments;encrypt=true;trustServerCertificate=true"
$env:DB_USER = "sa"
mvn clean test
mvn spring-boot:run
```

The app connects to SQL Server and Kafka (`localhost:9092`). Hibernate uses `ddl-auto=validate`;
the SQL Server schema is applied explicitly and is not automatically created by Hibernate.

The app runs at `http://localhost:8080`.

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- Actuator health: `http://localhost:8080/actuator/health`
- Actuator info: `http://localhost:8080/actuator/info`
- Actuator metrics: `http://localhost:8080/actuator/metrics`

## 2. Worklist integration

Payment Service uses the external Worklist Service at `WORKLIST_SERVICE_URL` (default `http://localhost:9090`).

- `POST /api/v1/worklist/tasks` creates an Accounts OTP authorization task.
- The create response's `wklTaskId` is saved against the individual `PaymentTxn`.
- `GET /api/v1/worklist/tasks/{taskId}` reads the current task status.
- `PUT /api/v1/worklist/tasks/{taskId}/status` updates the task status.

The task starts as `PENDING`. Accounts may move it to `IN_PROGRESS`. The Accounts UI submits OTP directly to Payment Service:

```http
POST http://localhost:8080/api/v1/payments/worklist-tasks/{taskId}/otp
Content-Type: application/json

{"otp":"123456"}
```

Payment Service validates the task/payment association and configured expiry, changes the Worklist task to `COMPLETED` through the Worklist PUT API, and invokes the existing ICICI submission workflow. OTP is not stored in the Payment database or logged.

The configurable Payment Service worklist-entry timeout is `payment.otp.expiry` (base value `PT15M`; dev profile overrides it to `PT5M`). This is an application worklist timeout, **not a statement of ICICI's bank OTP validity**. Confirm ICICI OTP validity with the bank before UAT/production.

When the configured deadline passes and the task remains `PENDING` or `IN_PROGRESS`, Payment Service rechecks its status, calls Worklist PUT with `CANCELLED`, fails the current `PaymentTxn`, and routes the original Payment Request through the existing retry policy.

Required Worklist task response fields: `wklTaskId`, `statusCode`. The response may include the other task metadata supplied by Worklist. The Worklist GET endpoint is assumed to return this task object directly; adapt `WorklistTaskResponse` if your service wraps it in an API envelope.

## 3. Payment flow and queues

- `NEW`: first payment attempt.
- `RETRY_1`, `RETRY_2`, `RETRY_3`: automatic retries with configured delays (base: 15 minutes, 30 minutes, 2 hours).
- `PENDING`: separate bank TransactionInquiry queue; uses the original `UNIQUEID` and never creates a new OTP.
- `MANUAL_INTERVENTION`: terminal operational state after retries are exhausted.

Only one request may occupy `IN_PROGRESS` / `AWAITING_OTP` at a time. `PaymentProcessorScheduler` handles the sequential payment workflow. `QueueDispatchScheduler` dispatches due retry and pending inquiry events. SQL Server `sp_getapplock` serializes payment execution across service instances.

## 4. Payment APIs

Submit a payment:

```bash
curl -X POST http://localhost:8080/api/v1/payments \
  -H 'Content-Type: application/json' \
  -d '{"req_ref_name":"SALARY","req_ref_id":123,"ben_ref_entity_name":"C3_EMPLOYEE","ben_ref_entity_id":456,"txn_categ_cdid":"1024","ben_acct_id":789,"amount":50000.00}'
```

Check payment state:

```bash
curl http://localhost:8080/api/v1/payments/SALARY/123
curl http://localhost:8080/api/v1/payments/queues
```

## 5. Dummy ICICI scenarios

`icici.client` remains `dummy` in the default/dev profile. Scenario mappings remain in `application.yml`.
The dummy bank simulates OTP, Create and TransactionInquiry responses; it does not prove ICICI packet compatibility or actual settlement.

## 6. Production readiness

The production profile is a scaffold, not approval for live payments. Before enabling real ICICI calls:
- Install ICICI-issued certificates, credentials, API keys and confirmed endpoint details.
- Implement approved account-number encryption at rest and secret management.
- Add production authentication/authorization and TLS for the Accounts OTP endpoint.
- Confirm ICICI OTP validity and bank response/retry rules.
- Test Worklist status transition and OTP submission end to end.
- Validate SQL Server backups, monitoring, durable Kafka delivery and operational reconciliation.

Do not expose the OTP endpoint publicly without authentication/authorization. Never log OTP values or bank credentials.
