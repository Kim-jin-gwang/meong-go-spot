package com.meonggo.backend.push.notification;

public final class ChatNotificationPublishException extends RuntimeException {
    private final boolean permanentTokenFailure;
    private final String safeCode;

    public ChatNotificationPublishException(boolean permanentTokenFailure, String safeCode) {
        super("Chat notification delivery failed");
        this.permanentTokenFailure = permanentTokenFailure;
        this.safeCode = safeCode;
    }

    public boolean permanentTokenFailure() {
        return permanentTokenFailure;
    }

    public String safeCode() {
        return safeCode;
    }
}
