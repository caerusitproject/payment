package com.company.payment.messaging;

import com.company.payment.domain.enums.QueueType;
import java.time.Instant;

/** Published whenever a request enters a retry / pending / manual queue. */
public record QueueEvent(Long pymntReqId, String reqRefName, Long reqRefId, QueueType queueType,
                         int retryCount, Instant nextAttemptAt, String reason) {}
