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
import com.company.payment.messaging.QueueEventPublisher;
import com.company.payment.worklist.*;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the complete lifecycle of a payment request.
 *
 * <p>This class is the payment state machine. It coordinates database records, beneficiary lookup,
 * payment-mode selection, bank API calls, OTP work items, retry routing and pending inquiries.
 * It does not expose REST endpoints; controllers, schedulers and Kafka consumers call its public methods.</p>
 *
 * <h2>How the entry points are used</h2>
 * <ul>
 *   <li>{@link #tick()} is called by {@code PaymentProcessorScheduler}. It advances an existing
 *       IN_PROGRESS/AWAITING_OTP request or starts one due NEW request.</li>
 *   <li>{@link #processRetryEvent(Long, QueueType)} is called by the retry Kafka consumer.
 *       It rechecks the database state and due time before starting a retry.</li>
 *   <li>{@link #processPendingEvent(Long)} is called by the separate pending Kafka consumer.
 *       It calls TransactionInquiry for the existing attempt and never creates another payment or OTP.</li>
 * </ul>
 *
 * <h2>Sequential payment rule</h2>
 * The payment scheduler and retry consumer must use the same distributed
 * {@code PaymentExecutionLock}. This class also checks for an IN_PROGRESS or AWAITING_OTP request
 * before starting work. Together, these controls are intended to ensure that only one payment is
 * actively using the Accounts OTP worklist at a time. The {@code synchronized} keyword alone only
 * protects this object inside one JVM; it is not a distributed lock.
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
    private final WorklistGateway worklist;
    private final RetryPolicy retryPolicy;
    private final PaymentProperties props;
    private final QueueEventPublisher events;

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
         * through their dedicated Kafka retry queues.
         *
         * Pending requests are also excluded because their inquiries are
         * processed independently by PendingQueueConsumer.
         */
        Optional<PaymentRequest> next =
                nextDue(QueueType.NEW, ProcStatus.QUEUED, now);

        /*
         * STEP 5: Start the payment if an eligible NEW request exists.
         *
         * If a request is found:
         *   -> startPayment() is called.
         *
         * If no request is found:
         *   -> Nothing happens in this scheduler cycle.
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
     * Entry point used by RetryQueueConsumer after a retry event is received from Kafka.
     *
     * <p>Kafka delivery is at-least-once, so this method must not trust the event by itself.
     * It reloads the payment request and verifies its current queue, status and due time. A stale
     * or duplicate event is therefore a no-op.</p>
     *
     * <p>The caller must acquire the shared PaymentExecutionLock before invoking this method.</p>
     *
     * @param paymentRequestId database ID of the original payment request
     * @param expectedQueue retry stage represented by the Kafka topic
     */
    public synchronized void processRetryEvent(Long paymentRequestId, QueueType expectedQueue) {
        PaymentRequest req = requests.findById(paymentRequestId).orElse(null);
        if (req == null || req.getQueueType() != expectedQueue
                || req.getProcStatus() != ProcStatus.RETRY_WAIT
                || req.getNextAttemptAt() == null
                || req.getNextAttemptAt().isAfter(Instant.now())) {
            return;
        }

        // Honour the configured order: NEW, RETRY_1, RETRY_2, RETRY_3.
        if (hasHigherPriorityDue(expectedQueue, Instant.now())) {
            return; // Queue dispatcher will publish another wake-up.
        }

        // Do not create another OTP work item while another payment is awaiting OTP.
        if (requests.findFirstByProcStatusIn(List.of(ProcStatus.IN_PROGRESS, ProcStatus.AWAITING_OTP)).isPresent()) {
            return; // Queue dispatcher will publish another wake-up when this request is due again.
        }
        startPayment(req);
    }

    /**
     * Prevents a lower-priority retry stage from overtaking work that is already due
     * in a higher-priority queue. Example: RETRY_2 must wait if NEW or RETRY_1 work is due.
     */
    private boolean hasHigherPriorityDue(QueueType currentQueue, Instant now) {
        // The configured order is shared by all retry consumers, even though each retry
        // stage has its own Kafka topic.
        for (QueueType higherPriority : props.processor().priority()) {
            if (higherPriority == currentQueue) {
                return false;
            }
            ProcStatus status = higherPriority == QueueType.NEW
                    ? ProcStatus.QUEUED : ProcStatus.RETRY_WAIT;
            if (requests.findFirstByQueueTypeAndProcStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscPymntReqIdAsc(
                    higherPriority, status, now).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Entry point used by PendingQueueConsumer.
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
        worklist.createOtpItem(new OtpWorkItem(
                req.getPymntReqId(),
                txn.getPymntTxnId(),
                txn.getUniqueId(),

                // Human-readable work item description.
                "Enter ICICI OTP for "
                        + req.getReqRefName() + "/"
                        + req.getReqRefId() + " - "
                        + req.getAmount() + " "
                        + req.getCurrency() + " - "
                        + props.categoryName(req.getTxnCategCdid()),

                // Payment amount displayed in the worklist.
                req.getAmount(),

                // OTP work item expiry time.
                Instant.now().plus(props.otp().expiry())
        ));

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
    private void continueAwaitingOtp(PaymentRequest req) {
        PaymentTxn txn = latestTxn(req);
        OtpStatus status = worklist.otpStatus(txn.getPymntTxnId());
        switch (status.state()) {
            case COMPLETED -> submit(req, txn, status.otp());
            case EXPIRED, MISSING, CONSUMED -> {
                failTxn(txn, "OTP was not entered in time");
                routeToFailureQueue(req, "OTP was not entered in time");   // the retry creates a new OTP work item
            }
            case OPEN -> { log.info("Waiting for OTP"); /* still waiting for the Accounts team; no other payment may
            start */ }
        }
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

        // Step 5: Call the existing protected submit() method.
        // The atomic database claim inside submit() ensures
        // that the API and scheduler cannot both submit this transaction.
        submit(req, txn, request.otp());
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
        worklist.consumeOtp(txn.getPymntTxnId());

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
     *   <li>PENDING_QUEUE: keep the same attempt and schedule another inquiry.</li>
     *   <li>FAILURE_QUEUE: mark the attempt failed and schedule the next eligible retry.</li>
     *   <li>MANUAL_QUEUE: preserve uncertainty and create a manual intervention task.</li>
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
            }
            case PENDING_QUEUE -> {           // Money may have moved: only TransactionInquiry may decide; never re-initiate.
                recordResponse(txn, body, TxnStatus.PENDING);
                long inquiries = interactions.countByUniqueIdAndApiOperation(txn.getUniqueId(), ApiOperation.STATUS_INQUIRY);
                if (fromInquiry && inquiries >= props.pending().maxInquiries()) {
                    toManual(req, "Still pending after " + inquiries + " inquiries: " + r.reason);
                } else {
                    req.setProcStatus(ProcStatus.PENDING);
                    req.setQueueType(QueueType.PENDING);
                    // Persist the next inquiry due time. The dispatcher polls frequently, but
                    // it publishes this request only after this 15-minute (configured) deadline.
                    req.setNextAttemptAt(Instant.now().plus(props.pending().inquiryDelay()));
                    req.setLastReason(r.reason);
                    save(req); // Queue dispatcher publishes when inquiry is due.
                }
            }
            case FAILURE_QUEUE -> {           // bank did not process it; retry later
                recordResponse(txn, body, TxnStatus.FAILED);
                routeToFailureQueue(req, r.reason);
            }
            case MANUAL_QUEUE -> {
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
     * <p>This method does not publish directly to Kafka. QueueDispatchScheduler later finds
     * the due database row and QueueEventPublisher sends a wake-up event to the retry topic.</p>
     */
    private void routeToFailureQueue(PaymentRequest req, String reason) {
        Optional<RetryPolicy.Step> step = retryPolicy.next(req.getRetryCount());
        if (step.isEmpty()) {
            toManual(req, "Retries exhausted (" + req.getRetryCount() + "): " + reason);
            return;
        }
        req.setQueueType(step.get().queue());
        req.setProcStatus(ProcStatus.RETRY_WAIT);
        req.setNextAttemptAt(Instant.now().plus(step.get().delay()));
        req.setLastReason(reason);
        save(req); // Retry dispatcher publishes only when nextAttemptAt is reached.
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
        Long txnId = txns.findFirstByPymntReqIdOrderByPymntTxnIdDesc(req.getPymntReqId()).map(PaymentTxn::getPymntTxnId).orElse(null);
        worklist.createManualInterventionItem(new ManualWorkItem(req.getPymntReqId(), txnId,
                "Manual intervention: " + req.getReqRefName() + "/" + req.getReqRefId()
                        + " - " + req.getAmount() + " " + req.getCurrency(), reason));
        events.publish(req);
        log.warn("{}:{} -> MANUAL_INTERVENTION: {}", req.getReqRefName(), req.getReqRefId(), reason);
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
            events.publish(req);
        } else {
            last.ifPresent(t -> failTxn(t, "Interrupted before bank submission"));
            routeToFailureQueue(req, "Interrupted before bank submission");
        }
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
        if (response != null && response.matches("\\d+")) {       // e.g. {"response":8012} is a code, not a status word
            respCode = response;
            response = "FAILURE";
        }
        txn.setResponse(response == null || response.isBlank() ? "UNKNOWN" : response.toUpperCase());
        txn.setRespCode(respCode);
        txn.setErrorCode(BankJson.field(body, "errorcode"));
        if (status == TxnStatus.SUCCESS) {
            String urn = BankJson.field(body, "urn");
            if (urn == null) urn = BankJson.field(body, "utrnumber");
            if (urn != null) txn.setUrn(urn);
            txn.setFailureMessage(null);
        } else {
            String message = BankJson.field(body, "message");
            txn.setFailureMessage(truncate(message != null ? message : (body == null ? "No response from bank" : null), 1000));
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
