package com.example.githubwebhook.dispatch;

import com.example.githubwebhook.discord.SendResult;

/** Classification of one send attempt (PD-01). Retry vs. permanent decides the job state in JobResultService. */
public enum AttemptOutcome {
    SUCCESS, RETRYABLE_FAIL, PERMANENT_FAIL;

    public static AttemptOutcome from(SendResult result) {
        if (result.delivered()) {
            return SUCCESS;
        }
        Integer status = result.httpStatus();
        // No status means an I/O error or timeout; those, 408, 429 and 5xx may succeed later. Every other 4xx and any
        // 3xx is permanent: retrying them risks Discord's invalid-request ban (D-06).
        return status == null || status == 408 || status == 429 || status >= 500 ? RETRYABLE_FAIL : PERMANENT_FAIL;
    }
}
