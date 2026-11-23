# Payment Service

Java 21 / Spring Boot payment orchestration service using SQL Server, Kafka NEW-request intake, ICICI bank APIs and the external Worklist Service.

## Scheduler model

| Scheduler | Frequency | Responsibility |
|---|---:|---|
| RETRY_1 | 15 minutes | Reads eligible first-retry requests from SQL Server |
| RETRY_2 | 1 hour | Reads eligible second-retry requests from SQL Server |
| RETRY_3 | 4 hours | Reads eligible third-retry requests from SQL Server |
| Pending inquiry | 5-second database poll | Finds due PENDING inquiries; each inquiry is scheduled 15 minutes after the previous result |
| OTP expiry | 2 minutes | Cancels expired external OTP tasks and applies the retry policy |

There are no retry Kafka topics, retry publishers or retry consumers. Pending inquiry is also database-driven and independent from retry processing. The only payment Kafka topic is the NEW request intake topic.

A retry stage is stored in `proc_status` as `RETRY_1`, `RETRY_2` or `RETRY_3`. The original `queue_type` column is retained in this source version because the original workflow and existing repository queries still use it as compatibility metadata; it is not used to publish or consume retry events. A follow-up migration should remove it after all query paths are converted to status-only selection.

## Kafka listener coordination

The retry flow uses `ListenerControl` to pause/resume the named NEW payment listener. A retry remains the active payment while waiting for OTP. NEW messages are consumed and persisted after the listener resumes; they are processed before the retry batch continues.

The current continuation logic resumes the listener and checks persisted NEW requests. In a production multi-instance deployment, use a single-instance deployment or a distributed execution lock and add a Kafka listener-idle/backlog coordination signal before assuming that all queued Kafka records have been persisted.

## Worklist integration

Payment Service does not own Worklist tables, entities, repositories or controllers. Worklist Service owns task records and statuses. Payment Service stores external task IDs on `c3_pymnt_txn`.

- Create task: `POST /api/v1/worklist/tasks`
- Find task by code: `GET /api/v1/worklist/tasks/{taskCode}?status={status}` (void/no-body contract supplied)
- Update status: `PUT /api/v1/worklist/tasks/{taskId}/status`

Accounts submits OTP to Payment Service using its own endpoint:

`POST /api/v1/payments/worklist-tasks/{taskId}/otp`

Request body:

```json
{ "otp": "123456" }
```

Payment Service validates the task/payment correlation and expiry, updates the Worklist task to `COMPLETED`, and submits the OTP to ICICI. The OTP is not stored in a local Worklist table.

Manual-intervention tasks use the same POST task API with task code `MANUAL_INTERVENTION_ON_PAYMENT`; the returned task ID is stored on the payment transaction.

## Database tables

The SQL Server schema contains six Payment Service tables:

1. `c3_payer`
2. `c3_pymnt_mode`
3. `pymnt_req`
4. `c3_benef_snapshot`
5. `c3_pymnt_txn`
6. `c3_bank_api_interaction`

The obsolete local `c3_pymnt_worklist_item` table is removed. Migration `V5__remove_local_worklist_persistence.sql` drops it from an existing database and adds the external manual-task correlation columns.

## Run locally

Set `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `WORKLIST_SERVICE_URL` and ICICI credentials.

```bash
mvn clean test
mvn spring-boot:run
```

Java 21 and Maven are required. Maven was not installed in the packaging environment, so compilation and tests have not been executed here.
