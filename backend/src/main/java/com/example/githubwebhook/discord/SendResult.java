package com.example.githubwebhook.discord;

/** Outcome of one Discord call. {@code error} never contains the webhook URL or an exception message (D-13). */
public record SendResult(boolean delivered, Integer httpStatus, String messageId, String error) {

    public static SendResult delivered(int httpStatus, String messageId) {
        return new SendResult(true, httpStatus, messageId, null);
    }

    public static SendResult httpFailure(int httpStatus, String error) {
        return new SendResult(false, httpStatus, null, error);
    }

    public static SendResult ioFailure(String error) {
        return new SendResult(false, null, null, error);
    }
}
