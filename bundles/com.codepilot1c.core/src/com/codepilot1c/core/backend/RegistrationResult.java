/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.backend;

/**
 * Result of registration, login or key rotation requests.
 */
public class RegistrationResult {

    private boolean success;
    private String apiKey;
    private String userId;
    private String email;
    private String error;
    private String errorCode;
    private long retryAfterSeconds;
    private double maxBudget;

    public static RegistrationResult success(String apiKey, String userId, double maxBudget) {
        return success(apiKey, userId, null, maxBudget);
    }

    public static RegistrationResult success(String apiKey, String userId, String email, double maxBudget) {
        RegistrationResult result = new RegistrationResult();
        result.success = true;
        result.apiKey = apiKey;
        result.userId = userId;
        result.email = email;
        result.maxBudget = maxBudget;
        return result;
    }

    public static RegistrationResult failure(String error) {
        return failure(error, null, 0);
    }

    public static RegistrationResult failure(String error, String errorCode, long retryAfterSeconds) {
        RegistrationResult result = new RegistrationResult();
        result.success = false;
        result.error = error;
        result.errorCode = errorCode;
        result.retryAfterSeconds = retryAfterSeconds;
        return result;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
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

    public double getMaxBudget() {
        return maxBudget;
    }
}
