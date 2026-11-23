package com.company.payment.service;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** Non-blocking single-instance guard for payment and retry execution. */
@Component
public class PaymentExecutionLock {
    private final AtomicBoolean running = new AtomicBoolean(false);

    public boolean executeIfAcquired(Runnable work) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        try {
            work.run();
            return true;
        } finally {
            running.set(false);
        }
    }
}
