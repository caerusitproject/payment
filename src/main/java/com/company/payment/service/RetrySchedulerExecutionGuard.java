package com.company.payment.service;

import com.company.payment.domain.enums.QueueType;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Single-instance guard shared by RETRY_1, RETRY_2 and RETRY_3 scheduler triggers. */
@Slf4j
@Component
public class RetrySchedulerExecutionGuard {
    private final AtomicBoolean anyRetrySchedulerRunning = new AtomicBoolean(false);
    private final Map<QueueType, AtomicBoolean> stageRunning = new EnumMap<>(QueueType.class);

    public RetrySchedulerExecutionGuard() {
        stageRunning.put(QueueType.RETRY_1, new AtomicBoolean(false));
        stageRunning.put(QueueType.RETRY_2, new AtomicBoolean(false));
        stageRunning.put(QueueType.RETRY_3, new AtomicBoolean(false));
    }

    public void runIfAvailable(QueueType stage, Runnable action) {
        AtomicBoolean own = stageRunning.get(stage);
        if (own == null || !own.compareAndSet(false, true)) {
            log.debug("Skipping {} scheduler trigger because this stage is already running", stage);
            return;
        }
        if (!anyRetrySchedulerRunning.compareAndSet(false, true)) {
            own.set(false);
            log.debug("Skipping {} scheduler trigger because another retry scheduler is running", stage);
            return;
        }
        try {
            action.run();
        } finally {
            anyRetrySchedulerRunning.set(false);
            own.set(false);
        }
    }
}
