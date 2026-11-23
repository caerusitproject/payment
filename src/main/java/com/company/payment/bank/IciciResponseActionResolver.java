package com.company.payment.bank;

import java.util.Locale;

/**
 * Converts an ICICI response into a safe next action.
 *
 * STATUS is authoritative whenever present. RESPONSE is used only to validate the
 * documented FAILURE contract and to recognize gateway errors that arrive without STATUS.
 */
public final class IciciResponseActionResolver {

    private IciciResponseActionResolver() {}

    public enum Disposition {
        SUCCESS,
        PENDING,
        RETRYABLE_FAILURE,
        MANUAL_INTERVENTION
    }

    public enum Action {
        MARK_SUCCESS(Disposition.SUCCESS),
        MARK_PENDING(Disposition.PENDING),
        SCHEDULE_RETRY(Disposition.RETRYABLE_FAILURE),
        REQUIRE_MANUAL_INTERVENTION(Disposition.MANUAL_INTERVENTION);

        public final Disposition disposition;

        Action(Disposition disposition) {
            this.disposition = disposition;
        }
    }

    public static final class Resolution {
        public final Action action;
        public final Disposition disposition;
        public final String reason;
        public final String code;
        public final String message;

        Resolution(Action action, String reason, String code, String message) {
            this.action = action;
            this.disposition = action.disposition;
            this.reason = reason;
            this.code = code;
            this.message = message;
        }

        @Override
        public String toString() {
            return "Resolution{disposition=" + disposition + ", action=" + action
                    + ", code='" + code + "', reason='" + reason + "'}";
        }
    }

    public static Resolution resolve(String json) {
        if (json == null || json.isBlank()) {
            // Request may have reached the bank. Only inquiry can establish its outcome.
            return result(Action.MARK_PENDING,
                    "No response body; verify the transaction through Transaction Inquiry.", "", "");
        }

        String response = BankJson.field(json, "response");
        String status = BankJson.field(json, "status");
        String message = BankJson.field(json, "message");
        String errorCode = BankJson.field(json, "errorcode");

        String normalizedResponse = normalize(response);
        String normalizedStatus = normalize(status);
        String code = errorCode == null ? "" : errorCode.trim();

        // 1. STATUS always wins over RESPONSE.
        if ("SUCCESS".equals(normalizedStatus)) {
            String reason = "FAILURE".equals(normalizedResponse)
                    ? "STATUS=SUCCESS takes priority; conflicting RESPONSE=FAILURE retained in audit fields."
                    : "Bank STATUS is SUCCESS.";
            return result(Action.MARK_SUCCESS, reason, code, message);
        }

        if ("PENDING".equals(normalizedStatus)) {
            return result(Action.MARK_PENDING,
                    "Bank STATUS is PENDING; schedule an independent Transaction Inquiry.", code, message);
        }

        if ("FAILURE".equals(normalizedStatus)) {
            return result(Action.SCHEDULE_RETRY,
                    "Bank STATUS is FAILURE; schedule the next configured retry attempt.", code, message);
        }

        // When RESPONSE=FAILURE, only the exact statuses FAILURE and PENDING are valid.
        // Never infer a transaction result from the free-text message.
        if ("FAILURE".equals(normalizedResponse)) {
            return result(Action.REQUIRE_MANUAL_INTERVENTION,
                    "Invalid ICICI response: RESPONSE=FAILURE requires STATUS=FAILURE or STATUS=PENDING.",
                    code, message);
        }

        // For RESPONSE=SUCCESS, ICICI also documents non-terminal processing statuses.
        if (isPending(normalizedStatus)) {
            return result(Action.MARK_PENDING,
                    "Bank STATUS is pending/processing; schedule an independent Transaction Inquiry.", code, message);
        }

        // 3. ICICI workbook lists gateway errors 8010/8012/8013 without STATUS.
        // They are ambiguous, so route to inquiry, not to retry.
        if ("8010".equals(response) || "8012".equals(response) || "8013".equals(response)) {
            return result(Action.MARK_PENDING,
                    "ICICI gateway/connection error without STATUS; verify through Transaction Inquiry.",
                    response, message);
        }

        // 4. Other missing or unknown statuses require investigation.
        if ("UNKNOWN".equals(normalizedStatus) || normalizedStatus.isEmpty()) {
            return result(Action.REQUIRE_MANUAL_INTERVENTION,
                    "Missing or UNKNOWN transaction STATUS; manual investigation is required.", code, message);
        }

        // DUPLICATE, REVERSED and UNCERTAIN are deliberately not auto-retried.
        return result(Action.REQUIRE_MANUAL_INTERVENTION,
                "Non-terminal or unsupported ICICI STATUS '" + status + "'; manual reconciliation is required.",
                code, message);
    }

    private static boolean isPending(String status) {
        return "PENDING".equals(status)
                || "PENDING FOR PROCESSING".equals(status)
                || "PENDING FOR APPROVAL".equals(status)
                || "PROCESSING".equals(status);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replace('_', ' ').toUpperCase(Locale.ROOT);
    }

    private static Resolution result(Action action, String reason, String code, String message) {
        return new Resolution(action, reason, code == null ? "" : code,
                message == null ? "" : message);
    }
}
