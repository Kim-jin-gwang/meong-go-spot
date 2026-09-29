package com.meonggo.backend.member.dto;

public record WithdrawalRequest(String currentPassword) {
    @Override
    public String toString() {
        return "WithdrawalRequest[REDACTED]";
    }
}
