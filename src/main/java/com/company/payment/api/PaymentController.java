package com.company.payment.api;

import com.company.payment.config.PaymentProperties;
import com.company.payment.domain.entity.PaymentRequest;
import com.company.payment.domain.enums.ProcStatus;
import com.company.payment.domain.enums.QueueType;
import com.company.payment.domain.enums.TxnStatus;
import com.company.payment.domain.repository.PaymentRequestRepository;
import com.company.payment.domain.repository.PaymentTxnRepository;
import com.company.payment.dto.request.OtpSubmitRequest;
import com.company.payment.messaging.PaymentRequestMessage;
import com.company.payment.service.ApiException;
import com.company.payment.service.PaymentWorkflowService;
import com.company.payment.service.PaymentExecutionLock;
import com.company.payment.exception.PaymentExecutionLockException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/payments", "/api/v1/payments"})
@RequiredArgsConstructor
public class PaymentController {

    public record Accepted(String reqRefName, Long reqRefId, String message) {
    }

    public record WorklistOtpRequest(@jakarta.validation.constraints.NotBlank
                                     @jakarta.validation.constraints.Pattern(regexp = "\\d{4,10}")
                                     String otp) {
    }

    public record TxnView(String uniqueId, int attemptNum, String transactionType, TxnStatus txnStatus, String response,
                          String respCode, String errorCode, String failureMessage, String urn) {
    }

    public record PaymentView(String reqRefName, Long reqRefId, ProcStatus status, QueueType queue, int retryCount,
                              Long paymentModeId, boolean completed, Instant nextAttemptAt, String lastReason,
                              List<TxnView> transactions) {
    }

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final PaymentProperties props;
    private final PaymentRequestRepository requests;
    private final PaymentTxnRepository txns;
    private final PaymentWorkflowService workflow;
    private final PaymentExecutionLock executionLock;

    /**
     * Submits a new payment request for  processing.
     * <p>
     * This API can be called by any integrated module (for example,
     * Recruitment, HR, or another business module).
     * <p>
     * Processing flow:
     * 1. Accept and validate the incoming payment request.
     * 2. Serialize the request into JSON.
     * 3. Publish the request to the main Kafka payment request topic.
     * 4. Return HTTP 202 ACCEPTED once Kafka acknowledges the message.
     * <p>
     * Actual payment processing, OTP generation, and bank communication
     * happen  through the payment workflow.
     * <p>
     * Endpoint: POST /api/v1/payments
     * <p>
     * Request Body: PaymentRequestMessage
     * Response: HTTP 202 ACCEPTED with request reference and queue status.
     */
    @PostMapping
    public ResponseEntity<Accepted> submit(
            @Valid @RequestBody PaymentRequestMessage msg) throws Exception {

        kafka.send(
                props.topics().request(),
                msg.key(),
                mapper.writeValueAsString(msg)
        ).get(5, TimeUnit.SECONDS);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new Accepted(
                        msg.reqRefName(),
                        msg.reqRefId(),
                        "Queued"
                ));
    }

    /**
     * Retrieves the complete payment processing details for a specific
     * business request.
     * <p>
     * This API allows the calling module or an authorized user to check
     * the current payment status and transaction attempt history.
     * <p>
     * Processing flow:
     * 1. Find the payment request using its reference name and reference ID.
     * 2. Return HTTP 404 if the payment request does not exist.
     * 3. Fetch all associated payment transactions in attempt order.
     * 4. Map transaction details into TxnView objects.
     * 5. Return payment status, queue information, retry details,
     * next scheduled processing time, and transaction history.
     * <p>
     * Endpoint: GET /api/v1/payments/{reqRefName}/{reqRefId}
     * <p>
     * Path Variables:
     * - reqRefName: Name of the originating business module.
     * - reqRefId: Unique payment reference ID from that module.
     * <p>
     * Response: PaymentView containing request status and transaction history.
     */
    @GetMapping("/{reqRefName}/{reqRefId}")
    public PaymentView get(
            @PathVariable String reqRefName,
            @PathVariable Long reqRefId) {

        PaymentRequest req = requests
                .findByReqRefNameAndReqRefId(reqRefName, reqRefId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "Unknown payment request"
                ));

        List<TxnView> list = txns
                .findByPymntReqIdOrderByAttemptNumAsc(req.getPymntReqId())
                .stream()
                .map(t -> new TxnView(
                        t.getUniqueId(),
                        t.getAttemptNum(),
                        t.getTransactionType(),
                        t.getTxnStatus(),
                        t.getResponse(),
                        t.getRespCode(),
                        t.getErrorCode(),
                        t.getFailureMessage(),
                        t.getUrn()
                ))
                .toList();

        return new PaymentView(
                req.getReqRefName(),
                req.getReqRefId(),
                req.getProcStatus(),
                req.getQueueType(),
                req.getRetryCount(),
                req.getPaymentModeId(),
                req.isCompleted(),
                req.getNextAttemptAt(),
                req.getLastReason(),
                list
        );
    }

    /**
     * Retrieves payment request counts grouped by queue type and processing status.
     * <p>
     * This API is intended for the Payment Service monitoring dashboard.
     * It helps operations and support teams monitor the payment workload
     * and identify requests waiting in different processing queues.
     * <p>
     * Processing flow:
     * 1. Fetch aggregated request counts from the database.
     * 2. Group the results by queue type and processing status.
     * 3. Return the counts as a list of queue summary records.
     * <p>
     * Endpoint: GET /api/v1/payments/queues
     * <p>
     * Response: List containing:
     * - queue: Payment queue name (NEW, RETRY_1, RETRY_2, RETRY_3, PENDING, etc.).
     * - status: Current payment processing status.
     * - count: Number of requests in that queue and status combination.
     */
    @GetMapping("/queues")
    public List<Map<String, Object>> queues() {

        List<Map<String, Object>> out = new ArrayList<>();

        for (Object[] row : requests.countByQueueAndStatus()) {
            out.add(Map.of(
                    "queue", row[0],
                    "status", row[1],
                    "count", row[2]
            ));
        }

        return out;
    }

    /**
     * Submits an OTP for a payment transaction awaiting OTP authorization.
     * <p>
     * This API is used by the Accounts team or an authorized application
     * to provide the OTP received for the current payment transaction.
     * <p>
     * Processing flow:
     * 1. Receive the payment request ID and OTP details.
     * 2. Acquire the shared SQL Server payment execution lock.
     * 3. Invoke PaymentWorkflowService.processOtp().
     * 4. Validate the payment request and transaction reference.
     * 5. Pass the OTP to the common payment submission workflow.
     * 6. The common submit() method atomically claims the transaction
     * before calling the ICICI payment API.
     * 7. Return HTTP 200 when OTP processing completes successfully.
     * <p>
     * The execution lock prevents the OTP API from racing with scheduler
     * or retry-consumer payment execution.
     * <p>
     * Endpoint: POST /api/v1/payments/{paymentRequestId}/otp
     * <p>
     * Path Variable:
     * - paymentRequestId: Internal payment request ID.
     * <p>
     * Request Body: OtpSubmitRequest
     * - transactionId: Internal payment transaction ID.
     * - otp: OTP supplied for the transaction.
     * <p>
     * Response: HTTP 200 when OTP processing is accepted by the workflow.
     * <p>
     * Note:
     * This endpoint does not directly call ICICI. The actual bank submission
     * is performed by PaymentWorkflowService.submit() after validation
     * and the atomic transaction claim.
     */
    /**
     * Accounts UI submits OTP using the external Worklist task ID.
     * Payment Service validates the task/payment association, updates Worklist status
     * to COMPLETED through its REST API, and invokes the existing ICICI submit workflow.
     */
    @PostMapping("/worklist-tasks/{taskId}/otp")
    public ResponseEntity<String> submitWorklistOtp(
            @PathVariable Long taskId,
            @Valid @RequestBody WorklistOtpRequest request) {

        boolean acquired = executionLock.executeIfAcquired(
                () -> workflow.processOtpByWorklistTaskId(taskId, request.otp())
        );
        if (!acquired) {
            throw new PaymentExecutionLockException(
                    "Payment processing is busy; retry the OTP submission shortly");
        }
        return ResponseEntity.ok("OTP accepted for payment processing");
    }

    @PostMapping("/{paymentRequestId}/otp")
    public ResponseEntity<String> submitOtp(
            @PathVariable Long paymentRequestId,
            @Valid @RequestBody OtpSubmitRequest request) {

        // Acquire the same SQL Server execution lock used by the scheduler
        // and retry consumer to prevent concurrent payment execution.
        boolean acquired = executionLock.executeIfAcquired(
                () -> workflow.processOtp(paymentRequestId, request)
        );

        // Reject the request if another workflow execution currently
        // holds the payment execution lock.
        if (!acquired) {
            throw new PaymentExecutionLockException(
                    "Payment processing is busy; retry the OTP submission shortly"
            );
        }

        return ResponseEntity.ok(
                "OTP submitted for payment processing"
        );
    }
}