# Payment Service — Agreed Processing Flow

## Retry schedulers

| Stage | Scheduler interval | Eligible status | Next retry status |
|---|---:|---|---|
| First retry | 15 minutes | `RETRY_1` | `RETRY_2` |
| Second retry | 1 hour | `RETRY_2` | `RETRY_3` |
| Third retry | 4 hours | `RETRY_3` | `MANUAL_INTERVENTION` or `FAILED` |

The scheduler interval is separate from the record's `next_attempt_at`. Each scheduler selects only records whose eligibility time has arrived. Each scheduler skips overlapping invocations of itself; the shared retry guard also prevents two retry stages from running concurrently. Missed invocations are not queued.

## Safe retry sequence

1. Pause the payment Kafka listener.
2. Process exactly one retry payment.
3. Finish the current operation at a safe boundary and persist its result.
4. Resume the Kafka listener.
5. Allow the listener to consume and persist waiting NEW messages.
6. Check the database for NEW requests.
7. If NEW requests exist, pause the listener again and process them one by one.
8. Resume the listener and check again.
9. When no NEW requests remain, continue the retry batch.

A retry waiting for OTP retains the active payment slot until OTP submission, failure or expiry. A NEW request may be consumed and persisted after the listener resumes, but it must wait until the active retry resolves.

## Pending inquiry is independent

A PENDING response or uncertain bank outcome is not a retry. The same transaction `UNIQUEID` is used for Transaction Inquiry. The first inquiry is scheduled 15 minutes after the pending result; subsequent inquiries are also 15 minutes apart. The pending scheduler polls SQL Server every 5 seconds only to discover due records.

Pending inquiry must not call ICICI Create, create a new transaction, or request a new OTP. The configured 24-hour pending horizon is currently represented by the configured inquiry limit and 15-minute interval.

## OTP expiry

OTP expiry is checked every 2 minutes. The external Worklist task is updated to `CANCELLED`, the current transaction is failed, and the retry policy is applied. The active payment slot is released only after the state transition and Worklist cancellation handling reach a safe boundary.

## Worklist ownership and OTP API

Worklist Service owns all task records and task statuses. Payment Service has no local Worklist entity/table/repository.

Accounts submits the entered OTP to:

`POST /api/v1/payments/worklist-tasks/{taskId}/otp`

Payment Service verifies that the task ID maps to a transaction in `AWAITING_OTP`, checks expiry, updates the Worklist task to `COMPLETED`, and calls the bank submission flow.

Manual-intervention tasks use the Worklist create API with `taskCode = MANUAL_INTERVENTION_ON_PAYMENT`. Payment Service stores the returned `wklTaskId` in `c3_pymnt_txn.manual_wkl_task_id`.

## Payment request states

- `QUEUED`
- `IN_PROGRESS`
- `AWAITING_OTP`
- `PENDING`
- `RETRY_1`
- `RETRY_2`
- `RETRY_3`
- `SUCCESS`
- `FAILED`
- `MANUAL_INTERVENTION`

`RETRY_WAIT` remains in the enum only for compatibility with existing persisted records; new retry routing writes the explicit `RETRY_1`, `RETRY_2` or `RETRY_3` status.
