package com.example.githubwebhook.discord;

import java.time.Duration;

/**
 * Outcome of one Discord call. {@code error} never contains the webhook URL or an exception message (D-13).
 * {@code retryAfter} is Discord's announced wait on a 429, null when unknown; {@code ioKind} is set only when no
 * HTTP answer arrived.
 */
public record SendResult(boolean delivered, Integer httpStatus, String messageId, String error, Duration retryAfter,
        IoKind ioKind) {

    public static SendResult delivered(int httpStatus, String messageId) {
        return new SendResult(true, httpStatus, messageId, null, null, null);
    }

    public static SendResult httpFailure(int httpStatus, String error) {
        return httpFailure(httpStatus, error, null);
    }

    public static SendResult httpFailure(int httpStatus, String error, Duration retryAfter) {
        return new SendResult(false, httpStatus, null, error, retryAfter, null);
    }

    public static SendResult ioFailure(IoKind kind, String error) {
        return new SendResult(false, null, null, error, null, kind);
    }
}
