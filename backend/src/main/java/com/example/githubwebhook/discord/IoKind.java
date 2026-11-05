package com.example.githubwebhook.discord;

/** Kind of I/O failure on a Discord call, with the stable code stored as the start of the error text (D-11). */
public enum IoKind {
    CONNECT("CONNECT_ERROR"), RESPONSE_TIMEOUT("READ_TIMEOUT"), OTHER("IO_ERROR");

    private final String code;

    IoKind(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
