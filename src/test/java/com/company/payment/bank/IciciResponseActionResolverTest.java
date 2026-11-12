package com.company.payment.bank;

import static com.company.payment.bank.IciciResponseActionResolver.Disposition.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.company.payment.bank.IciciResponseActionResolver.Resolution;
import org.junit.jupiter.api.Test;

class IciciResponseActionResolverTest {

    @Test void statusSuccessWinsEvenWhenResponseFailure() {
        Resolution r = IciciResponseActionResolver.resolve(
                "{\"RESPONSE\":\"FAILURE\",\"STATUS\":\"SUCCESS\"}");
        assertEquals(SUCCESS, r.disposition);
    }

    @Test void statusPendingWinsEvenWhenResponseFailure() {
        Resolution r = IciciResponseActionResolver.resolve(
                "{\"RESPONSE\":\"FAILURE\",\"STATUS\":\"PENDING\"}");
        assertEquals(PENDING_QUEUE, r.disposition);
    }

    @Test void statusFailureGoesToRetryQueue() {
        Resolution r = IciciResponseActionResolver.resolve(
                "{\"RESPONSE\":\"SUCCESS\",\"STATUS\":\"FAILURE\"}");
        assertEquals(FAILURE_QUEUE, r.disposition);
    }

    @Test void pendingForProcessingIsPending() {
        Resolution r = IciciResponseActionResolver.resolve(
                "{\"RESPONSE\":\"SUCCESS\",\"STATUS\":\"Pending For Processing\"}");
        assertEquals(PENDING_QUEUE, r.disposition);
    }

    @Test void responseFailureWithMissingStatusGoesToManual() {
        Resolution r = IciciResponseActionResolver.resolve(
                "{\"RESPONSE\":\"FAILURE\",\"MESSAGE\":\"Host not available\"}");
        assertEquals(MANUAL_QUEUE, r.disposition);
    }

    @Test void responseFailureWithInvalidStatusGoesToManual() {
        Resolution r = IciciResponseActionResolver.resolve(
                "{\"RESPONSE\":\"FAILURE\",\"STATUS\":\"UNKNOWN\"}");
        assertEquals(MANUAL_QUEUE, r.disposition);
    }

    @Test void gatewayTimeoutWithoutStatusUsesPendingInquiry() {
        Resolution r = IciciResponseActionResolver.resolve(
                "{\\\"success\\\":false,\\\"response\\\":8012,\\\"message\\\":\\\"BACKEND_CONNECTION_TIMEOUT\\\"}");
        assertEquals(PENDING_QUEUE, r.disposition);
    }

    @Test void noBodyMeansOutcomeUnknownAndPendingInquiry() {
        assertEquals(PENDING_QUEUE, IciciResponseActionResolver.resolve(null).disposition);
    }
}
