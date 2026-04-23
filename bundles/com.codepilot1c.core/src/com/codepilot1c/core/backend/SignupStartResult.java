/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.backend;

/**
 * Result of the first signup step when the backend sends a verification code.
 */
public class SignupStartResult {

    private boolean success;
    private String error;
    private String errorCode;
    private long retryAfterSeconds;
    private boolean verificationRequired;
    private long expiresInSeconds;
    private long resendAvailableInSeconds;

    public static SignupStartResult success(boolean verificationRequired, long expiresInSeconds,
            long resendAvailableInSeconds) {
        SignupStartResult result = new SignupStartResult();
        result.success = true;
        result.verificationRequired = verificationRequired;
        result.expiresInSeconds = expiresInSeconds;
        result.resendAvailableInSeconds = resendAvailableInSeconds;
        return result;
    }

    public static SignupStartResult failure(String error, String errorCode, long retryAfterSeconds) {
        SignupStartResult result = new SignupStartResult();
        result.success = false;
        result.error = error;
        result.errorCode = errorCode;
        result.retryAfterSeconds = retryAfterSeconds;
        return result;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getError() {
        return error;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public boolean isVerificationRequired() {
        return verificationRequired;
    }

    public long getExpiresInSeconds() {
        return expiresInSeconds;
    }

    public long getResendAvailableInSeconds() {
        return resendAvailableInSeconds;
    }
}
