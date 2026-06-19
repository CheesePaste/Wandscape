package com.wsteam.wandscape.shared.data;

public record ExecutionResult(boolean success, String errorMessage) {
    public static ExecutionResult ok() {
        return new ExecutionResult(true, null);
    }

    public static ExecutionResult fail(String message) {
        return new ExecutionResult(false, message);
    }
}
