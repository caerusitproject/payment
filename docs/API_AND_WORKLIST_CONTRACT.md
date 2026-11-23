# Payment Service API and Worklist Contract

## Submit payment

`POST /api/v1/payments`

Publishes a new payment instruction to the NEW-request Kafka topic. Returns HTTP 202 after Kafka acknowledgement.

## Read payment

`GET /api/v1/payments/{reqRefName}/{reqRefId}`

Returns request status and bank transaction history.

## Accounts OTP submission API

`POST /api/v1/payments/worklist-tasks/{taskId}/otp`

Request:

```json
{
  "otp": "123456"
}
```

This is the Payment Service's own endpoint. Accounts submits the OTP entered by the user to Payment Service; Payment Service validates the task/payment association and expiry, updates the external Worklist task status to `COMPLETED`, and submits the OTP to ICICI.

The OTP is not persisted in a local Worklist table.

## Worklist Service APIs used by Payment Service

### Create task

`POST http://localhost:9090/api/v1/worklist/tasks`

Example OTP task request:

```json
{
  "taskCode": "PAYMENT_OTP_AUTHORIZATION",
  "taskName": "Authorize Payment Transaction",
  "refEntityName": "PAYMENT",
  "refEntityId": "PAYMENT_REQUEST_ID",
  "linkedInstEntityName": "PAYMENT",
  "linkedInstEntityId": 1001,
  "assigneeRoleCd": "ACCOUNTS"
}
```

A new task is expected to return `statusCode = PENDING` and `wklTaskId`.

### Find task by code

`GET http://localhost:9090/api/v1/worklist/tasks/{taskCode}?status={status}`

This is the endpoint shape supplied by the Worklist Service. Its response body was not included in the supplied controller snippet. The Payment Service adapter currently expects a `WorklistTaskResponse`; confirm the actual response type before production.

### Update task status

`PUT http://localhost:9090/api/v1/worklist/tasks/{taskId}/status`

Request examples:

```json
{ "statusCode": "IN_PROGRESS" }
```

```json
{ "statusCode": "COMPLETED" }
```

```json
{ "statusCode": "CANCELLED" }
```

Allowed statuses also include `PENDING`.

## Manual-intervention task

Payment Service uses the same create-task endpoint with:

`taskCode = MANUAL_INTERVENTION_ON_PAYMENT`

The returned `wklTaskId` is stored in `c3_pymnt_txn.manual_wkl_task_id`.

## Important ownership rule

The Worklist Service owns task records and statuses. Payment Service stores only external task identifiers and the payment-side expiry/correlation information. No local Worklist table, entity or repository is used.
