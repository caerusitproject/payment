package com.company.payment.service;

import com.company.payment.bank.*;
import com.company.payment.bank.IciciResponseActionResolver.Resolution;
import com.company.payment.beneficiary.BeneficiaryAccount;
import com.company.payment.beneficiary.BeneficiaryAccountProvider;
import com.company.payment.beneficiary.BeneficiaryNotFoundException;
import com.company.payment.common.crypto.AccountMasker;
import com.company.payment.config.PaymentProperties;
import com.company.payment.domain.entity.*;
import com.company.payment.domain.enums.*;
import com.company.payment.domain.repository.*;
import com.company.payment.dto.request.OtpSubmitRequest;
import com.company.payment.exception.InvalidPaymentRequestException;
import com.company.payment.exception.NoEligiblePaymentModeException;
import com.company.payment.exception.PaymentAlreadyProcessedException;
import com.company.payment.exception.PaymentRequestNotFoundException;
import com.company.payment.exception.PaymentTransactionNotFoundException;
import com.company.payment.exception.InvalidOtpException;
import com.company.payment.gateway.worklist.dto.WorklistTaskCreateRequest;
import com.company.payment.gateway.worklist.dto.WorklistTaskResponse;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

/**
 * Orchestrates the complete lifecycle of a payment request.
 *
 * <p>This class is the payment state machine. It coordinates database records, beneficiary lookup,
 * payment-mode selection, bank API calls, OTP work items, retry routing and pending inquiries.
 * It does not expose REST endpoints; controllers, schedulers and Kafka consumers call its public methods.</p>
 *
 * <h2>How the entry points are used</h2>
 * <ul>
 *   <li>{@link #startNextPayment()} is called directly by the NEW Kafka listener after persistence.
 *       It starts the next due NEW request without a NEW-queue polling scheduler.</li>
 *   <li>{@link #processDueRetryQueue(QueueType)} is called by the DB-backed retry schedulers.
 *       It rechecks the database state and due time before starting a retry.</li>
 *   <li>{@link #processPendingEvent(Long)} is called by the independent SQL-backed pending inquiry scheduler.
 *       It calls TransactionInquiry for the existing attempt and never creates another payment or OTP.</li>
 * </ul>
 *
 * <h2>Sequential payment rule</h2>
 * The Kafka NEW listener, three DB-backed retry schedulers and OTP submission endpoint share
 * the sequential payment slot. This is designed for the agreed single-instance deployment.
 *
 * <h2>Bank-call safety</h2>
 * State is persisted before an external bank call. In particular, a transaction is marked SUBMITTED
 * before calling the payment Create API. If the application stops after that point, recovery routes
 * the attempt to TransactionInquiry instead of blindly submitting the same payment again.
 *
 * <p>Important: this class does not hold a database transaction open across a bank network call.
 * Database persistence and external bank processing cannot be made one atomic transaction; the
 * workflow therefore uses persisted states, unique transaction IDs and inquiry-based recovery.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentWorkflowService {

    private static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");
    private static final Pattern ACCOUNT = Pattern.compile("^\\d{9,18}$");

    private final PaymentRequestRepository requests;
    private final PaymentTxnRepository txns;
    private final PayerRepository payers;
    private final BenefSnapshotRepository snapshots;
    private final BankApiInteractionRepository interactions;
    private final BeneficiaryAccountProvider beneficiaries;
    private final PaymentModeResolver modeResolver;
    private final BankApiService bank;
    // External Worklist REST integration for OTP tasks.
    private final com.company.payment.gateway.worklist.WorklistGateway worklistGateway;

    private final RetryPolicy retryPolicy;
    private final PaymentProperties props;
    private final ListenerControl listenerControl;
    private final PaymentExecutionLock executionLock;

    /** Retry stage whose multi-item run is paused while a higher-priority NEW request runs. */
    private volatile QueueType activeRetryQueue;

    @Value("${worklist.service.task-code:PAYMENT_OTP_AUTHORIZATION}")
    private String worklistTaskCode;

    @Value("${worklist.service.task-name:Authorize Payment Transaction}")
    private String worklistTaskName;

    @Value("${worklist.service.ref-entity-name:BENEFICIARY}")
    private String worklistRefEntityName;

    @Value("${worklist.service.linked-instance-entity-name:ONBOARDING}")
    private String linkedInstanceEntityName;

    @Value("${worklist.service.linked-instance-entity-id:1001}")
    private Long linkedInstanceEntityId;

    @Value("${worklist.service.assignee-role-code:ACCOUNTS}")
    private String worklistAssigneeRoleCode;

    @Value("${worklist.service.payment-transaction-entity-name:c3_pmnt_txn}")
    private String paymentTransactionEntityName;

    // -------------------------------------------------------------------------
    // 1. SCHEDULER ENTRY POINT
    // -------------------------------------------------------------------------

    /**
     * Advances the payment workflow by one scheduler pass.
     *
     * <p>Order is important:</p>
     * <ol>
     *   <li>First look for a payment already IN_PROGRESS or AWAITING_OTP.</li>
     *   <li>If it is waiting for OTP, check whether Accounts completed, expired or left it open.</li>
     *   <li>If it is IN_PROGRESS, treat it as an interrupted execution and recover safely.</li>
     *   <li>Only when no payment is in flight, pick the oldest due NEW request.</li>
     * </ol>
     *
     * <p>Pending inquiries are intentionally not processed here. They are handled by
     * {@link #processPendingEvent(Long)} through a separate Kafka consumer, so a slow inquiry
     * cannot block the sequential payment/OTP pipeline.</p>
     */
    /** Starts the next persisted NEW request immediately after Kafka intake commits it. */
    public synchronized void startNextPayment() {
        executionLock.executeIfAcquired(this::tick);
    }

    /** Expires overdue OTP work items only; this never polls or starts NEW payments. */
    public synchronized void expireAwaitingOtpRequests() {
        requests.findFirstByProcStatusIn(List.of(ProcStatus.AWAITING_OTP))
                .ifPresent(this::continueAwaitingOtp);
    }

    public synchronized void tick() {

        // Capture the current time once for consistent database eligibility checks.
        Instant now = Instant.now();

        /*
         * STEP 1: Check whether another payment is already being processed.
         *
         * IN_PROGRESS:
         * The payment workflow has started but has not reached a final state.
         * It may require crash recovery if the previous execution was interrupted.
         *
         * AWAITING_OTP:
         * The bank has generated an OTP and the Accounts team must enter it.
         *
         * We must handle this payment first because starting another payment
         * could create multiple active OTP work items.
         */
        Optional<PaymentRequest> inFlight =
                requests.findFirstByProcStatusIn(
                        List.of(
                                ProcStatus.IN_PROGRESS,
                                ProcStatus.AWAITING_OTP
                        )
                );

        // STEP 2: If an existing payment is found, handle it before new payments.
        if (inFlight.isPresent()) {

            // Retrieve the payment request that is currently in progress.
            PaymentRequest req = inFlight.get();

            /*
             * STEP 3: Decide how to continue the existing payment.
             *
             * AWAITING_OTP:
             * Check the OTP work item:
             * - COMPLETED: Submit the payment to the bank.
             * - OPEN: Continue waiting for Accounts.
             * - EXPIRED/MISSING/CONSUMED: Route the attempt through retry policy.
             *
             * IN_PROGRESS:
             * The application may have stopped during payment execution.
             * Check the latest transaction status and recover safely.
             */
            if (req.getProcStatus() == ProcStatus.AWAITING_OTP) {

                // Continue the payment after checking its OTP work item.
                continueAwaitingOtp(req);

            } else {

                // Recover a payment interrupted during its previous execution.
                recoverInterrupted(req);
            }

            /*
             * IMPORTANT:
             * Always return after handling an existing payment.
             *
             * This prevents the scheduler from starting another payment
             * while the current payment is waiting for OTP or recovery.
             */
            return;
        }

        /*
         * STEP 4: No payment is currently occupying the active payment slot.
         *
         * Find the oldest eligible NEW payment request.
         *
         * Conditions:
         * - queue_type = NEW
         * - proc_status = QUEUED
         * - next_attempt_at <= current time
         *
         * Retry requests are not selected here because they are triggered
         * through their dedicated database-backed retry schedulers.
         *
         * Pending requests are also excluded because their inquiries are
         * processed independently by PendingInquiryScheduler.
         */
        Optional<PaymentRequest> next =
                nextDue(QueueType.NEW, ProcStatus.QUEUED, now);

        /*
         * STEP 5: Start the oldest eligible NEW request, if one exists.
         *
         * This method is called by the NEW Kafka listener after intake.store()
         * commits the request. It is not a NEW-queue polling scheduler.
         *
         * When a retry stage is paused, NEW requests are still processed here.
         * The retry stage is resumed by resumeListenerAndStartQueuedNew() only
         * after the current NEW payment resolves and no persisted NEW request
         * is waiting.
         */
        next.ifPresent(this::startPayment);
    }

    /**
     * Finds the oldest request in the requested queue whose scheduled time has arrived.
     * The database query orders by next_attempt_at and then request ID for deterministic selection.
     */
    private Optional<PaymentRequest> nextDue(QueueType queue, ProcStatus status, Instant now) {
        return requests.findFirstByQueueTypeAndProcStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscPymntReqIdAsc(queue, status, now);
    }


    /**
     * Starts the oldest eligible payment request for the specified retry stage
     * directly from SQL Server.
     *
     * This method is invoked by RETRY_1, RETRY_2 and RETRY_3 schedulers.
     * Retry requests are fetched directly from the database; no retry Kafka
     * topic or retry Kafka consumer is involved.
     *
     * Execution rules:
     * 1. Only one retry cycle can be active at a time.
     * 2. NEW payment requests always have priority over retries.
     * 3. The NEW Kafka listener must be paused before a retry starts.
     * 4. The retry execution remains active during asynchronous OTP processing.
     * 5. Scheduler triggers received during an active retry cycle are skipped.
     * 6. Retry priority is RETRY_1 -> RETRY_2 -> RETRY_3.
     */



//    RETRY Scheduler
//      |
//    v
//    Check NEW requests
//      |
//              +---- NEW exists ----> Resume NEW Kafka listener
//      |                            |
//              |                            v
//      |                      Process NEW payments
//      |                            |
//              |                      Check NEW again
//      |                            |
//              |                      Resume RETRY
//      |
//              +---- No NEW -------> Pause NEW Kafka listener
//                                   |
//    v
//    Start retry payment
//                                   |
//    v
//    Wait for OTP
//                                   |
//    v
//    Submit to ICICI
//                                   |
//    v
//    Resolve payment
//                                   |
//    v
//    Check NEW requests again




    public synchronized void processDueRetryQueue(QueueType expectedQueue) {

        // Accept only the three supported retry stages.
        if (expectedQueue != QueueType.RETRY_1
                && expectedQueue != QueueType.RETRY_2
                && expectedQueue != QueueType.RETRY_3) {

            throw new IllegalArgumentException(
                    "Not a retry queue: " + expectedQueue);
        }

        Instant now = Instant.now();

        // Another retry cycle is already active.
        if (activeRetryQueue != null) {

            log.debug(
                    "Skipping {} trigger because {} retry cycle is already active",
                    expectedQueue,
                    activeRetryQueue);

            return;
        }

        // NEW requests always have higher priority than retry requests.
        boolean newRequestAvailable =
                requests.findFirstByQueueTypeAndProcStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscPymntReqIdAsc(
                        QueueType.NEW,
                        ProcStatus.QUEUED,
                        now
                ).isPresent();

        if (newRequestAvailable) {

            // Reserve this retry stage until NEW processing is completed.
            activeRetryQueue = expectedQueue;

            log.info(
                    "NEW payment requests are available. Deferring {} and processing NEW requests first.",
                    expectedQueue);

            // Process NEW through the common execution-lock entry point.
            startNextPayment();

            return;
        }

        // Do not initiate a retry if another payment is already active.
        // This includes a NEW payment waiting for OTP.
        boolean paymentAlreadyActive =
                requests.findFirstByProcStatusIn(
                        List.of(
                                ProcStatus.IN_PROGRESS,
                                ProcStatus.AWAITING_OTP
                        )
                ).isPresent();

        if (paymentAlreadyActive) {

            log.debug(
                    "Skipping {} because another payment attempt is active",
                    expectedQueue);

            return;
        }

        // Preserve retry priority:
        // RETRY_2 cannot overtake an eligible RETRY_1.
        // RETRY_3 cannot overtake an eligible RETRY_1 or RETRY_2.
        if (hasHigherPriorityDue(expectedQueue, now)) {

            log.debug(
                    "Skipping {} because a higher-priority retry is eligible",
                    expectedQueue);

            return;
        }

        // Fetch the oldest eligible request from this retry queue.
        Optional<PaymentRequest> eligibleRequest =
                requests.findFirstByQueueTypeAndProcStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscPymntReqIdAsc(
                        expectedQueue,
                        ProcStatus.valueOf(expectedQueue.name()),
                        now
                );

        if (eligibleRequest.isEmpty()) {

            log.debug(
                    "No eligible payment requests found in {}",
                    expectedQueue);

            return;
        }

        PaymentRequest req = eligibleRequest.get();

        // Reserve the retry execution before starting payment.
        activeRetryQueue = expectedQueue;

        try {

            log.info(
                    "Starting {} from database: paymentRequestId={}",
                    expectedQueue,
                    req.getPymntReqId());

            // startPayment() handles listener pause.
            // activeRetryQueue remains reserved until resolution.
            startPayment(req);

        } catch (Exception ex) {

            log.error(
                    "Retry initiation failed for queue {} and paymentRequestId={}. "
                            + "Routing to manual intervention to avoid unsafe resubmission.",
                    expectedQueue,
                    req.getPymntReqId(),
                    ex);

            // Do not blindly retry after an uncertain bank/API operation.
            toManual(
                    req,
                    "Retry initiation failed; manual review required: "
                            + truncate(ex.getMessage(), 700)
            );
        }
    }



    /**
     * Prevents a lower-priority retry stage from overtaking work that is already due
     * in a higher-priority queue. Example: RETRY_2 must wait if NEW or RETRY_1 work is due.
     */
    private boolean hasHigherPriorityDue(QueueType currentQueue, Instant now) {
        // The configured order is shared by the three database-backed retry schedulers.
        for (QueueType higherPriority : props.processor().priority()) {
            if (higherPriority == currentQueue) {
                return false;
            }
            ProcStatus status = higherPriority == QueueType.NEW
                    ? ProcStatus.QUEUED : ProcStatus.RETRY_1;
            if (requests.findFirstByQueueTypeAndProcStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscPymntReqIdAsc(
                    higherPriority, status, now).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Entry point invoked by the SQL-backed PendingInquiryScheduler.
     *
     * <p>This pipeline is deliberately independent of the payment/OTP execution lock. It only
     * performs TransactionInquiry for the latest attempt's existing UNIQUEID. It must never call
     * Create, generate a new OTP, or create a new transaction attempt.</p>
     *
     * @param paymentRequestId original payment request ID carried in the pending queue event
     */
    public void processPendingEvent(Long paymentRequestId) {
        Instant now = Instant.now();

        // Claim the due inquiry atomically before calling ICICI. The dispatcher may publish
        // the same due row more than once while Kafka events are in flight. Only the first
        // consumer can move nextAttemptAt forward; duplicate/stale events become no-ops.
        Instant leaseUntil = now.plus(props.pending().inquiryDelay());
        int claimed = requests.claimDuePendingInquiry(
                paymentRequestId,
                QueueType.PENDING,
                ProcStatus.PENDING,
                now,
                leaseUntil
        );

        if (claimed == 0) {
            log.debug("Pending inquiry {} is not due or has already been claimed", paymentRequestId);
            return;
        }

        // Reload because the atomic JPQL update clears the persistence context.
        PaymentRequest req = requests.findById(paymentRequestId)
                .orElseThrow(() -> new PaymentRequestNotFoundException(paymentRequestId));

        // Only TransactionInquiry is permitted here. It uses the original attempt's UNIQUEID.
        // If the process crashes during the bank call, the lease expires and the dispatcher
        // can safely issue another inquiry; it must never re-submit the payment.
        inquire(req);
    }

    // -------------------------------------------------------------------------
    // 2. START A PAYMENT ATTEMPT: PAYER -> BENEFICIARY -> MODE -> TXN -> OTP
    // -------------------------------------------------------------------------

    /**
     * Prepares one bank attempt and requests its OTP.
     *
     * <p>For an initial payment, retry_count remains zero. For a retry, it is set to the retry
     * stage number. Every call creates a fresh PaymentTxn row and UNIQUEID, including retries.
     * The original PaymentRequest remains the business instruction being processed.</p>
     *
     * <p>Method sequence:</p>
     * <ol>
     *   <li>Mark the request IN_PROGRESS and persist it.</li>
     *   <li>Find an active payer account for the request currency.</li>
     *   <li>Fetch and validate beneficiary details from the configured provider.</li>
     *   <li>Persist a beneficiary snapshot so this attempt uses stable beneficiary data.</li>
     *   <li>Resolve the eligible payment mode from amount and IFSC.</li>
     *   <li>Create and persist a new transaction attempt with a new UNIQUEID.</li>
     *   <li>Call the bank OTP API.</li>
     *   <li>Create an Accounts OTP work item and move the request to AWAITING_OTP.</li>
     * </ol>
     *
     * <p>Any configuration or validation issue that cannot be safely auto-corrected is routed
     * to manual intervention. The bank Create payment API is not called from this method.</p>
     */
    private void startPayment(PaymentRequest req) {


        // Pause NEW Kafka intake while this payment occupies the execution slot.
        listenerControl.pause();
        // Step 1: Update the retry count.
        //
        // For an initial payment (NEW queue), retryCount remains 0.
        // For a retry request (RETRY_1, RETRY_2, RETRY_3),
        // retrieve the corresponding retry number from the queue type.
        //
        // Example:
        // NEW     -> retryCount = 0
        // RETRY_1 -> retryCount = 1
        // RETRY_2 -> retryCount = 2
        // RETRY_3 -> retryCount = 3
        //
        // This count is later used to calculate the transaction attempt number.
        if (req.getQueueType() != QueueType.NEW) {
            req.setRetryCount(RetryPolicy.retryNumber(req.getQueueType()));
        }

        // Step 2: Mark the payment request as IN_PROGRESS.
        //
        // Persist this status before performing external operations.
        // This helps the scheduler identify an active payment request
        // and prevents another payment from starting while this one
        // is being processed.
        req.setProcStatus(ProcStatus.IN_PROGRESS);
        save(req);

        // Log the payment reference and current attempt number.
        // retryCount starts from 0, so adding 1 gives the actual attempt number.
        log.info("Processing {}:{} (attempt {})",
                req.getReqRefName(),
                req.getReqRefId(),
                req.getRetryCount() + 1);

        // Step 3: Find an active payer account for the requested currency.
        //
        // The payer account represents the company's configured source
        // bank account from which the payment will be initiated.
        //
        // If multiple active payer accounts exist for the same currency,
        // the repository selects the account with the lowest payer ID.
        Optional<Payer> payerOpt =
                payers.findFirstByCurrencyAndActiveStatusCdidTrueOrderByPayerIdAsc(
                        req.getCurrency());

        // A payment cannot proceed without an active payer account.
        // Move the request to manual intervention instead of continuing.
        if (payerOpt.isEmpty()) {
            toManual(req, "No active payer configured for currency " + req.getCurrency());
            return;
        }

        // Extract the configured payer account.
        Payer payer = payerOpt.get();

        // Step 4: Fetch beneficiary account details.
        //
        // Beneficiary details are retrieved from the source system
        // using the beneficiary account ID stored in the payment request.
        BeneficiaryAccount account;

        try {
            account = beneficiaries.fetch(req.getBenAcctId());

        } catch (BeneficiaryNotFoundException e) {

            // If the beneficiary does not exist, the payment cannot proceed.
            // Send the request for manual intervention.
            toManual(req, e.getMessage());
            return;
        }

        // Step 5: Validate beneficiary details.
        //
        // validate() checks whether the beneficiary information is suitable
        // for initiating a bank payment.
        //
        // If validation fails, it returns an error message.
        // If validation succeeds, it returns null.
        String invalid = validate(account);

        if (invalid != null) {

            // Do not initiate a payment with invalid beneficiary details.
            toManual(req, "Beneficiary rejected: " + invalid);
            return;
        }

        // Step 6: Create a beneficiary snapshot.
        //
        // A snapshot stores the beneficiary details used for this particular
        // payment attempt.
        //
        // This is important because beneficiary details in the source system
        // may change later. The snapshot preserves the details associated
        // with this transaction.
        //
        // Note: benAccPlain stores the account number in plain text in the
        // current local-development implementation. Production must use
        // appropriate encryption and secure access controls.
        Instant now = Instant.now();

        BenefSnapshot snapshot = snapshots.save(BenefSnapshot.builder()

                // Original beneficiary account reference.
                .benRefId(req.getBenAcctId())

                // Beneficiary account holder name.
                .benAcctHolName(account.accountHolderName())

                // Beneficiary account number.
                .benAccPlain(account.accountNumber())

                // Masked account number for safer display and logging.
                .benAccMasked(AccountMasker.mask(account.accountNumber()))

                // Beneficiary bank IFSC code.
                .ifscCode(account.ifscCode())

                // Beneficiary bank name.
                .bankName(account.bankName())

                // Transaction category associated with the beneficiary.
                .txnCategCdid(account.txnCategCdid())

                // Snapshot creation and last-update timestamps.
                .createdTs(now)
                .updateTs(now)
                .build());

        // Step 7: Resolve the appropriate payment mode.
        //
        // PaymentModeResolver selects an eligible payment mode based on:
        // - Payment amount.
        // - Beneficiary IFSC code.
        // - Active payment mode configuration.
        //
        // For example, an eligible ICICI beneficiary transfer may use FT.
        PaymentMode mode;

        try {
            mode = modeResolver.resolve(req.getAmount(), account.ifscCode());

        } catch (NoEligiblePaymentModeException e) {

            // If no configured payment mode supports this amount,
            // the payment cannot proceed automatically.
            toManual(req, e.getMessage());
            return;
        }

        // Store the selected payment mode against the payment request.
        // This allows the selected mode to be referenced later.
        req.setPaymentModeId(mode.getPymntModeId());
        save(req);

        // Step 8: Create a new payment transaction record.
        //
        // Every payment attempt gets its own transaction record.
        // This includes retries.
        //
        // A new UNIQUEID is generated for every attempt so that
        // each bank transaction can be tracked independently.
        PaymentTxn txn = txns.save(PaymentTxn.builder()

                // Link the transaction to the original payment request.
                .pymntReqId(req.getPymntReqId())

                // Company's source payer account.
                .payerId(payer.getPayerId())

                // Beneficiary account reference.
                .beneficiaryId(req.getBenAcctId())

                // Snapshot containing the beneficiary details used in this attempt.
                .beniSnapId(snapshot.getBeniSnapId())

                // Selected payment mode.
                .paymentModeId(mode.getPymntModeId())

                // Payment amount and currency.
                .amount(req.getAmount())
                .currency(req.getCurrency())

                // Payment remarks sent to the bank.
                .remarks(remarks(req))

                // Attempt number:
                // Initial attempt = 1
                // First retry     = 2
                // Second retry    = 3
                // Third retry     = 4
                .attemptNum(req.getRetryCount() + 1)

                // Convert the configured payment mode into the
                // corresponding ICICI transaction type.
                .transactionType(
                        PaymentModeCode.valueOf(mode.getModeCode())
                                .iciciTransactionType())

                // Generate a new unique transaction identifier.
                //
                // Removing hyphens from UUID produces 32 characters.
                // Adding the "TXN" prefix makes the total length 35 characters.
                // This remains within the configured 40-character bank limit.
                .uniqueId("TXN" + UUID.randomUUID().toString().replace("-", ""))

                // Initial transaction status before calling the bank.
                .txnStatus(TxnStatus.INITIATED)

                // Indicates that no bank request has been submitted yet.
                .response("NOT_SENT")

                // Record when this transaction attempt was initiated.
                .initiatedTs(Instant.now())
                .build());

        // Step 9: Call the ICICI Create-OTP API.
        //
        // The bank generates the OTP for the transaction.
        // The application does not generate the actual ICICI OTP.
        //
        // The UNIQUEID and payment amount are sent in the OTP request.
        BankCallResult otp = bank.createOtp(
                new OtpRequest(txn.getUniqueId(), req.getAmount()));

        // Step 10: Check whether the OTP request could be sent.
        //
        // If sent() is false, the request could not be prepared/sent,
        // for example because the required encryption operation failed.
        //
        // In this situation, do not continue with OTP worklist creation.
        if (!otp.sent()) {

            // Mark the transaction as failed.
            failTxn(txn, "Create-OTP request could not be encrypted");

            // Move the payment request to manual intervention.
            toManual(req,
                    "Create-OTP request could not be encrypted (check key material)");
            return;
        }

        // Step 11: Check the bank's OTP generation response.
        //
        // A successful HTTP/API call does not automatically mean
        // that the bank successfully generated the OTP.
        // Therefore, inspect the response field returned by ICICI.
        if (!"SUCCESS".equalsIgnoreCase(
                BankJson.field(otp.body(), "response"))) {

            // Save the bank response and mark the transaction as failed.
            recordResponse(txn, otp.body(), TxnStatus.FAILED);

            // Create-OTP failed, and the actual payment Create API
            // has not been called.
            //
            // Therefore, this attempt has not submitted the payment.
            // Route it through the configured retry policy.
            routeToFailureQueue(
                    req,
                    "ICICI OTP generation failed: "
                            + BankJson.field(otp.body(), "message"));
            return;
        }

        // Step 12: Mark the transaction as OTP_REQUESTED.
        //
        // This indicates that the bank accepted the OTP generation request.
        // The transaction is now waiting for the Accounts team to provide
        // the OTP through the worklist.
        txn.setTxnStatus(TxnStatus.OTP_REQUESTED);
        touch(txn);

        // Step 13: Create an OTP work item for the Accounts team.
        //
        // The work item contains:
        // - Original payment request ID.
        // - Current transaction ID.
        // - Bank UNIQUEID.
        // - Payment reference and amount.
        // - Currency and transaction category.
        // - OTP expiry timestamp.
        //
        // The worklist is used by the Accounts team to enter the OTP
        // required to authorize this payment attempt.
        //
        // Only one payment is allowed to wait for OTP at a time
        // according to the payment execution workflow.
        LocalDateTime taskCreatedAt = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime taskExpiresAt = taskCreatedAt.plus(props.otp().expiry());

        WorklistTaskCreateRequest taskRequest =
                WorklistTaskCreateRequest.builder()
                        .taskCode(worklistTaskCode)
                        .taskName(worklistTaskName)

                        // Beneficiary reference from c3_pmnt_req
                        .refEntityName(req.getReqRefName())
                        .refEntityId(String.valueOf(req.getReqRefId()))

                        // Actual payment transaction from c3_pmnt_txn
                        .linkedInstEntityName(paymentTransactionEntityName)
                        .linkedInstEntityId(txn.getPymntTxnId())

                        .assigneeRoleCd(worklistAssigneeRoleCode)
                        .build();

        WorklistTaskResponse taskResponse = worklistGateway.createTask(taskRequest);
        if (taskResponse == null || taskResponse.getWklTaskId() == null) {
            failTxn(txn, "Worklist task creation did not return wklTaskId");
            routeToFailureQueue(req, "Worklist task creation failed");
            return;
        }

        txn.setWklTaskId(taskResponse.getWklTaskId());
        txn.setWklTaskCreatedTs(taskCreatedAt);
        txn.setOtpTaskExpiresTs(taskExpiresAt);
        touch(txn);

        if (taskResponse.getStatusCode() != null
                && !"PENDING".equalsIgnoreCase(taskResponse.getStatusCode())) {
            log.warn("Worklist task {} was created with unexpected initial status {}",
                    taskResponse.getWklTaskId(), taskResponse.getStatusCode());
        }

        // Step 14: Mark the payment request as AWAITING_OTP.
        //
        // The payment execution flow will wait for the Accounts team
        // to submit the OTP.
        //
        // The next processing stage is handled by continueAwaitingOtp(),
        // which checks the OTP work item status before submitting
        // the actual payment request to ICICI.
        req.setProcStatus(ProcStatus.AWAITING_OTP);
        save(req);
    }

    // -------------------------------------------------------------------------
    // 3. WAIT FOR ACCOUNTS OTP AND SUBMIT THE PAYMENT
    // -------------------------------------------------------------------------

    /**
     * Checks the OTP work item for the current transaction.
     *
     * <ul>
     *   <li>COMPLETED: OTP is available, so submit the payment.</li>
     *   <li>OPEN: Accounts has not entered the OTP yet; keep the current payment in flight.</li>
     *   <li>EXPIRED/MISSING/CONSUMED: this attempt cannot continue; route it through retry policy.</li>
     * </ul>
     *
     * A new payment is not started while this request remains AWAITING_OTP.
     */
    /**
     * Handles a PaymentRequest that is currently waiting for OTP authorization.
     *
     * This method is called by the payment scheduler when the request
     * status is AWAITING_OTP.
     *
     * It checks the external Worklist task status and determines whether
     * to continue waiting, expire the task, or handle a changed task status.
     */
    private void continueAwaitingOtp(PaymentRequest req) {
        // OTP is submitted to Payment Service by Accounts; no local Worklist polling is needed.
        PaymentTxn txn = latestTxn(req);
        if (txn.getWklTaskId() == null) {
            log.error("Payment transaction {} has no external Worklist task ID", txn.getPymntTxnId());
            toManual(req, "OTP task reference is missing");
            return;
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (txn.getOtpTaskExpiresTs() != null && !now.isBefore(txn.getOtpTaskExpiresTs())) {
            // The database expiry timestamp is authoritative. Cancel the external task by ID.
            worklistGateway.updateTaskStatus(txn.getWklTaskId(), "CANCELLED");
            failTxn(txn, "OTP entry window expired");
            routeToFailureQueue(req, "OTP entry window expired");
            return;
        }

        // Keep AWAITING_OTP and the active payment slot until Accounts submits OTP or it expires.
        log.debug("Payment request {} remains AWAITING_OTP for Worklist task {}",
                req.getPymntReqId(), txn.getWklTaskId());
    }

    /**
     * Processes the OTP received from the Accounts team.
     *
     * Validates the payment request and transaction before
     * triggering the actual bank payment submission.
     *
     * @param paymentRequestId original payment request ID
     * @param request          submitted OTP and transaction ID
     */
    public void processOtp(
            Long paymentRequestId,
            OtpSubmitRequest request) {

        // Reject a missing/invalid OTP before changing any payment or worklist state.
        if (request == null || request.otp() == null || !request.otp().matches("\\d{4,10}")) {
            throw new InvalidOtpException("OTP must contain 4-10 digits");
        }

        // Step 1: Fetch the original payment request.
        PaymentRequest req = requests.findById(paymentRequestId)
                .orElseThrow(() ->
                        new PaymentRequestNotFoundException(paymentRequestId));

        // Step 2: Verify that the payment is waiting for OTP.
        if (req.getProcStatus() != ProcStatus.AWAITING_OTP) {
            throw new PaymentAlreadyProcessedException(
                    "Payment is not waiting for OTP");
        }

        // Step 3: Fetch the latest transaction for this payment.
        PaymentTxn txn = latestTxn(req);

        // Step 4: Verify that the submitted transaction ID
        // matches the transaction waiting for OTP.
        if (!Objects.equals(
                txn.getPymntTxnId(),
                request.transactionId())) {

            throw new InvalidPaymentRequestException(
                    "Transaction ID does not match");
        }

        // Preserve the legacy route, but delegate to the new Worklist-task flow
        // so every OTP submission validates and completes the external task.
        if (txn.getWklTaskId() == null) {
            throw new InvalidPaymentRequestException("No Worklist task is associated with this transaction");
        }
        processOtpByWorklistTaskId(txn.getWklTaskId(), request.otp());
    }



    /**
     * Processes the OTP submitted by the Accounts user against a Worklist task.
     *
     * This method validates the OTP, verifies the associated payment request
     * and transaction, checks task expiry and Worklist status, and then
     * submits the OTP to the bank through the existing payment flow.
     */
    public void processOtpByWorklistTaskId(Long taskId, String otp) {
        if (taskId == null || otp == null || !otp.matches("\\d{4,10}")) {
            throw new InvalidOtpException("OTP must contain 4-10 digits");
        }

        PaymentTxn txn = txns.findByWklTaskId(taskId)
                .orElseThrow(() -> new PaymentTransactionNotFoundException(taskId));
        PaymentRequest req = requests.findById(txn.getPymntReqId())
                .orElseThrow(() -> new PaymentRequestNotFoundException(txn.getPymntReqId()));

        if (req.getProcStatus() != ProcStatus.AWAITING_OTP
                || txn.getTxnStatus() != TxnStatus.OTP_REQUESTED) {
            throw new PaymentAlreadyProcessedException("Payment is not awaiting OTP");
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (txn.getOtpTaskExpiresTs() != null && !now.isBefore(txn.getOtpTaskExpiresTs())) {
            worklistGateway.updateTaskStatus(taskId, "CANCELLED");
            failTxn(txn, "OTP entry window expired");
            routeToFailureQueue(req, "OTP entry window expired");
            throw new InvalidOtpException("OTP entry window has expired");
        }

        // The Accounts API call is the OTP submission signal; update the external task before bank submission.
        worklistGateway.updateTaskStatus(taskId, "COMPLETED");
        submit(req, txn, otp);
    }

    /**
     * Submits the payment to the bank after Accounts has supplied the OTP.
     *
     * <p>Crash-safety sequence:</p>
     * <ol>
     *   <li>Persist request as IN_PROGRESS.</li>
     *   <li>Persist transaction as SUBMITTED before making the network call.</li>
     *   <li>Consume the OTP work item so it cannot be reused.</li>
     *   <li>Load the payer and the beneficiary snapshot used by this attempt.</li>
     *   <li>Call BankApiService.submit(), which delegates to the selected IciciClient.</li>
     *   <li>Resolve the bank response and route the request to its next state.</li>
     * </ol>
     *
     * <p>If the bank outcome is unknown, do not resubmit. The resolver routes it to pending
     * inquiry or manual intervention so the original UNIQUEID can be checked.</p>
     */
    private void submit(PaymentRequest req, PaymentTxn txn, String otp) {


        // STEP 1: Atomically claim the transaction for submission.
        //
        // Only one execution path (OTP API or Scheduler) can
        // change the transaction from OTP_REQUESTED to SUBMITTED.
        int claimed = txns.markSubmittedIfOtpRequested(
                txn.getPymntTxnId(),
                TxnStatus.OTP_REQUESTED,
                TxnStatus.SUBMITTED
        );

        // STEP 2: If another execution path has already claimed
        // this transaction, stop processing.
        //
        // This prevents duplicate ICICI payment submissions.
        if (claimed == 0) {
            log.warn(
                    "Transaction {} is already claimed for submission. Skipping duplicate execution.",
                    txn.getPymntTxnId()
            );
            return;
        }

        // STEP 3: Mark the payment request as IN_PROGRESS.
        req.setProcStatus(ProcStatus.IN_PROGRESS);
        save(req);

        // STEP 4: Consume the OTP work item.
        // This prevents the OTP from being reused.
        // OTP is supplied directly by the Accounts UI to Payment Service in the new flow.
        // Worklist status is updated through the external Worklist REST API. No local OTP is stored.

        Payer payer = payers.findById(txn.getPayerId()).orElseThrow();
        BenefSnapshot snapshot = snapshots.findById(txn.getBeniSnapId()).orElseThrow();
        BankCallResult result = bank.submit(new SubmitRequest(
                txn.getUniqueId(),
                // LOCAL MODE: account numbers are read from the *_plain columns by design.
                // Do not use real customer data with this profile. Production must use the
                // approved encryption-at-rest / secure account-data provider implementation.
                payer.getAccNumPlain(),
                snapshot.getBenAccPlain(),
                snapshot.getIfscCode(),
                txn.getAmount(),
                txn.getCurrency(),
                txn.getTransactionType(),
                snapshot.getBenAcctHolName(),
                txn.getRemarks(),
                otp,
                txn.getBeneficiaryId()));

        if (!result.sent()) {
            failTxn(txn, "Transaction request could not be encrypted");
            toManual(req, "Transaction request could not be encrypted (check key material); nothing was sent");
            return;
        }
        // STATUS/RESPONSE interpretation is centralized in the resolver.
        // A missing/empty response is treated as an unknown outcome and must not trigger blind resubmission.
        Resolution r = IciciResponseActionResolver.resolve(result.body());
        log.info("Bank response for {}:{} -> {}", req.getReqRefName(), req.getReqRefId(), r);
        applyResolution(req, txn, r, result.body(), false);
    }

    // -------------------------------------------------------------------------
    // 4. PENDING QUEUE: INQUIRE ABOUT THE EXISTING BANK ATTEMPT
    // -------------------------------------------------------------------------

    /**
     * Calls TransactionInquiry for the latest transaction attempt.
     *
     * <p>Notice that this method passes only the existing UNIQUEID. It does not create a new
     * PaymentTxn, call Create, or request OTP. The inquiry result is resolved using the same
     * response-routing logic as the initial submission.</p>
     */
    private void inquire(PaymentRequest req) {
        PaymentTxn txn = latestTxn(req);
        BankCallResult result = bank.inquire(new InquiryRequest(txn.getUniqueId()));
        Resolution r = IciciResponseActionResolver.resolve(result.body());
        log.info("Inquiry for {}:{} -> {}", req.getReqRefName(), req.getReqRefId(), r);
        applyResolution(req, txn, r, result.body(), true);
    }

    // -------------------------------------------------------------------------
    // 5. APPLY THE BANK RESULT TO THE PAYMENT STATE MACHINE
    // -------------------------------------------------------------------------

    /**
     * Applies the action selected by IciciResponseActionResolver.
     *
     * <p>The resolver is responsible for interpreting the bank response (STATUS has priority
     * over RESPONSE). This method is responsible for persisting the resulting business state:</p>
     * <ul>
     *   <li>SUCCESS: close the payment request and clear its queue schedule.</li>
     *   <li>PENDING: keep the same attempt and schedule another inquiry.</li>
     *   <li>RETRYABLE_FAILURE: mark the attempt failed and schedule the next eligible retry.</li>
     *   <li>MANUAL_INTERVENTION: preserve uncertainty and create a manual intervention task.</li>
     * </ul>
     *
     * @param fromInquiry true when this result came from TransactionInquiry; used for inquiry limit
     */
    private void applyResolution(PaymentRequest req, PaymentTxn txn, Resolution r, String body, boolean fromInquiry) {
        switch (r.disposition) {
            case SUCCESS -> {
                recordResponse(txn, body, TxnStatus.SUCCESS);
                req.setProcStatus(ProcStatus.SUCCESS);
                req.setQueueType(QueueType.NONE);
                req.setNextAttemptAt(null);
                req.setCompleted(true);
                req.setCompleteTs(Instant.now());
                req.setLastReason("Payment successful");
                save(req);
                resumeListenerAndStartQueuedNew();
            }
            case PENDING -> {           // Money may have moved: only TransactionInquiry may decide; never re-initiate.
                recordResponse(txn, body, TxnStatus.PENDING);
                long inquiries = interactions.countByUniqueIdAndApiOperation(txn.getUniqueId(), ApiOperation.STATUS_INQUIRY);
                if (fromInquiry && inquiries >= props.pending().maxInquiries()) {
                    toManual(req, "Still pending after " + inquiries + " inquiries: " + r.reason);
                } else {
                    req.setProcStatus(ProcStatus.PENDING);
                    req.setQueueType(QueueType.PENDING);
                    // Persist the next inquiry due time. The pending dispatch scheduler publishes this request only after this 15-minute deadline.
                    req.setNextAttemptAt(Instant.now().plus(props.pending().inquiryDelay()));
                    req.setLastReason(r.reason);
                    save(req); // Pending inquiry dispatches when the due time arrives.
                }
                resumeListenerAndStartQueuedNew();
            }
            case RETRYABLE_FAILURE -> {           // bank did not process it; retry later
                recordResponse(txn, body, TxnStatus.FAILED);
                routeToFailureQueue(req, r.reason);
            }
            case MANUAL_INTERVENTION -> {
                // Preserve uncertainty: do not falsely persist FAILED when the bank status is unclear.
                String bankStatus = BankJson.field(body, "status");
                TxnStatus storedStatus = "REVERSED".equalsIgnoreCase(bankStatus)
                        ? TxnStatus.REVERSED : TxnStatus.PENDING;
                recordResponse(txn, body, storedStatus);
                toManual(req, r.reason);
            }
        }
    }

    /**
     * Calculates the next retry stage and stores its due time.
     *
     * <p>Configured policy: retry 1 after 15 minutes, retry 2 after 30 minutes,
     * retry 3 after 2 hours. If there is no next retry step, the request is sent to
     * MANUAL_INTERVENTION. Initial attempt is not counted as a retry.</p>
     *
     * <p>The retry schedulers read the due row directly from SQL Server. No retry Kafka
     * event is published or consumed.</p>
     */
    private void routeToFailureQueue(PaymentRequest req, String reason) {
        Optional<RetryPolicy.Step> step = retryPolicy.next(req.getRetryCount());
        if (step.isEmpty()) {
            toManual(req, "Retries exhausted (" + req.getRetryCount() + "): " + reason);
            return;
        }
        req.setQueueType(step.get().queue());
        req.setProcStatus(ProcStatus.valueOf(step.get().queue().name()));
        req.setNextAttemptAt(Instant.now().plus(step.get().delay()));
        req.setLastReason(reason);
        save(req); // The matching retry scheduler starts it when nextAttemptAt is reached.
        resumeListenerAndStartQueuedNew();
        log.info("{}:{} -> {} at {}", req.getReqRefName(), req.getReqRefId(), req.getQueueType(), req.getNextAttemptAt());
    }

    /**
     * Stops automatic processing and creates a manual intervention work item.
     *
     * <p>The request is marked MANUAL_INTERVENTION, its next-attempt time is cleared, and an
     * operator-facing work item is created. The manual Kafka event is an integration/notification
     * signal; the database work item is the actionable task. No automatic retry is scheduled.</p>
     */
    private void toManual(PaymentRequest req, String reason) {
        req.setProcStatus(ProcStatus.MANUAL_INTERVENTION);
        req.setQueueType(QueueType.MANUAL);
        req.setNextAttemptAt(null);
        req.setCompleted(false);
        req.setLastReason(reason);
        save(req);

        // Get the latest payment transaction from c3_pymnt_txn
        PaymentTxn latest = txns
                .findFirstByPymntReqIdOrderByPymntTxnIdDesc(req.getPymntReqId())
                .orElse(null);

        if (latest == null) {
            log.error("No payment transaction found for paymentRequestId={}",
                    req.getPymntReqId());
            return;
        }

        WorklistTaskResponse task = worklistGateway.createTask(
                WorklistTaskCreateRequest.builder()
                        .taskCode("MANUAL_INTERVENTION_ON_PAYMENT")
                        .taskName("Manual intervention on payment")

                        // Reference entity: beneficiary reference from c3_pmnt_req
                        .refEntityName(req.getReqRefName())
                        .refEntityId(String.valueOf(req.getReqRefId()))

                        // Linked instance: actual payment transaction
                        .linkedInstEntityName(paymentTransactionEntityName)
                        .linkedInstEntityId(latest.getPymntTxnId())

                        .assigneeRoleCd("ACCOUNTS")
                        .build()
        );

        if (task != null && task.getWklTaskId() != null) {
            latest.setManualWklTaskId(task.getWklTaskId());
            latest.setManualWklTaskCreatedTs(task.getCreatedTs());
            txns.save(latest);
        }

        resumeListenerAndStartQueuedNew();

        log.warn("{}:{} -> MANUAL_INTERVENTION: {}",
                req.getReqRefName(), req.getReqRefId(), reason);
    }

    // -------------------------------------------------------------------------
    // 6. RECOVER AN EXECUTION INTERRUPTED BY APPLICATION RESTART/CRASH
    // -------------------------------------------------------------------------

    /**
     * Determines whether the previous execution stopped before or after the bank submission.
     *
     * <ul>
     *   <li>Last transaction SUBMITTED: bank may have received it. Mark it PENDING and schedule
     *       TransactionInquiry using the same UNIQUEID.</li>
     *   <li>Not SUBMITTED: the bank Create call was not reached. Mark the attempt failed and
     *       route through retry policy.</li>
     * </ul>
     *
     * This distinction prevents a potentially duplicated payment after an uncertain network call.
     */
    private void recoverInterrupted(PaymentRequest req) {
        Optional<PaymentTxn> last = txns.findFirstByPymntReqIdOrderByPymntTxnIdDesc(req.getPymntReqId());
        if (last.isPresent() && last.get().getTxnStatus() == TxnStatus.SUBMITTED) {
            PaymentTxn txn = last.get();
            txn.setTxnStatus(TxnStatus.PENDING);
            txn.setFailureMessage("Interrupted after submit; verifying through inquiry");
            touch(txn);
            req.setProcStatus(ProcStatus.PENDING);
            req.setQueueType(QueueType.PENDING);
            req.setNextAttemptAt(Instant.now().plus(props.pending().inquiryDelay()));
            req.setLastReason(txn.getFailureMessage());
            save(req);
            } else {
            last.ifPresent(t -> failTxn(t, "Interrupted before bank submission"));
            routeToFailureQueue(req, "Interrupted before bank submission");
        }
    }


    /**
     * Releases the completed payment attempt and gives NEW payments priority.
     *
     * Flow:
     * 1. Resume NEW Kafka intake.
     * 2. If a NEW request is already persisted, start it before another retry.
     * 3. Keep the paused retry stage recorded while NEW requests are processed.
     * 4. When no persisted NEW request is waiting, continue the paused retry stage.
     * 5. If no retry stage is paused, continue normal NEW processing.
     *
     * The NEW listener is expected to pause itself immediately after storing
     * each NEW message. This method checks already-persisted NEW requests.
     * A Kafka consumer-idle/backlog signal is additionally required if the
     * application must prove that no unread Kafka records remain before retrying.
     */
    private synchronized void resumeListenerAndStartQueuedNew() {

        // Let the NEW listener consume waiting Kafka records first.
        listenerControl.resume();

        Instant now = Instant.now();
        boolean newRequestWaiting = requests
                .findFirstByQueueTypeAndProcStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscPymntReqIdAsc(
                        QueueType.NEW,
                        ProcStatus.QUEUED,
                        now)
                .isPresent();

        if (newRequestWaiting) {
            // Start the oldest persisted NEW request. Its completion will
            // come back here and check for the next NEW request.
            tick();
            return;
        }

        // No persisted NEW request is waiting. Continue the retry stage
        // that was interrupted for NEW processing, if one is active.
        QueueType pausedRetry = activeRetryQueue;
        if (pausedRetry != null) {
            activeRetryQueue = null;
            processDueRetryQueue(pausedRetry);
            return;
        }

        // No retry stage is waiting to resume; continue normal NEW processing.
        tick();
    }

    // -------------------------------------------------------------------------
    // 7. SMALL PERSISTENCE, VALIDATION AND FORMATTING HELPERS
    // -------------------------------------------------------------------------

    /** Returns the newest attempt belonging to the original payment request. */
    private PaymentTxn latestTxn(PaymentRequest req) {
        return txns.findFirstByPymntReqIdOrderByPymntTxnIdDesc(req.getPymntReqId())
                .orElseThrow(() -> new PaymentTransactionNotFoundException(req.getPymntReqId()));
    }

    /**
     * Extracts commonly used fields from the bank response and persists them on the attempt.
     * Numeric RESPONSE values are treated as response codes, not status words (for example 8012).
     * The caller supplies the transaction status after the resolver has decided the disposition.
     */
    private void recordResponse(PaymentTxn txn, String body, TxnStatus status) {
        String response = BankJson.field(body, "response");
        String respCode = BankJson.field(body, "responsecode");

        if (response != null && response.matches("\\d+")) {
            respCode = response;
            response = "FAILURE";
        }

        txn.setResponse(
                response == null || response.isBlank()
                        ? "UNKNOWN"
                        : response.toUpperCase()
        );

        txn.setRespCode(respCode);
        txn.setErrorCode(BankJson.field(body, "errorcode"));

        // Store ICICI URN for the transaction
        String urn = BankJson.field(body, "urn");
        if (urn != null && !urn.isBlank()) {
            txn.setUrn(urn);
        }

        if (status == TxnStatus.SUCCESS) {
            // Store ICICI UTRNUMBER only on success
            String utrNumber = BankJson.field(body, "utrnumber");

            if (utrNumber != null && !utrNumber.isBlank()) {
                txn.setUtrNumber(utrNumber);
            }

            txn.setFailureMessage(null);
        } else {
            String message = BankJson.field(body, "message");
            txn.setFailureMessage(
                    truncate(
                            message != null
                                    ? message
                                    : (body == null ? "No response from bank" : null),
                            1000
                    )
            );
        }

        txn.setTxnStatus(status);
        touch(txn);
    }

    /** Marks one bank attempt failed and stores a bounded diagnostic message. */
    private void failTxn(PaymentTxn txn, String message) {
        txn.setTxnStatus(TxnStatus.FAILED);
        txn.setFailureMessage(truncate(message, 1000));
        touch(txn);
    }

    /** Updates the transaction modification timestamp and persists the attempt. */
    private void touch(PaymentTxn txn) {
        txn.setUpdatedTs(Instant.now());
        txns.save(txn);
    }

    /** Persists the current payment-request state after a workflow transition. */
    private void save(PaymentRequest req) {
        requests.save(req);
    }

    /** Builds the short bank remarks field from configured category name and business reference. */
    private String remarks(PaymentRequest req) {
        String text = (props.categoryName(req.getTxnCategCdid()) + " " + req.getReqRefId()).replaceAll("[^A-Za-z0-9 ]", " ");
        return truncate(text, 30);
    }

    /**
     * Performs basic format validation before an attempt is created.
     * These are local format checks only; they do not verify account ownership or bank account status.
     */
    private static String validate(BeneficiaryAccount a) {
        if (a.ifscCode() == null || !IFSC.matcher(a.ifscCode()).matches()) return "invalid IFSC format";
        if (a.accountNumber() == null || !ACCOUNT.matcher(a.accountNumber()).matches()) return "invalid account number format";
        if (a.accountHolderName() == null || a.accountHolderName().isBlank()) return "missing account holder name";
        return null;
    }

    /** Prevents unbounded bank/error text from being stored in bounded database columns. */
    private static String truncate(String s, int max) {
        return s == null ? null : (s.length() > max ? s.substring(0, max) : s);
    }



}