package com.example.githubwebhook.dispatch;

import com.example.githubwebhook.discord.SendResult;

/** Classification of one send attempt (PD-01). The job itself is FAILED for every failure in phase 1 (D-02). */
public enum AttemptOutcome {
    SUCCESS, RETRYABLE_FAIL, PERMANENT_FAIL;

    public static AttemptOutcome from(SendResult result) {
        if (result.delivered()) {
            return SUCCESS;
        }
        Integer status = result.httpStatus();
        // No status means an I/O error or timeout; those, 429 and 5xx may succeed later.
        return status == null || status == 429 || status >= 500 ? RETRYABLE_FAIL : PERMANENT_FAIL;
    }
}
