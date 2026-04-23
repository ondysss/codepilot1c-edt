/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider;

/**
 * Exception thrown when an LLM provider encounters an error.
 */
public class LlmProviderException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final String errorType;
    private final String errorCode;
    private RateLimitDetails rateLimitDetails;

    /**
     * Creates a new exception with a message.
     *
     * @param message the error message
     */
    public LlmProviderException(String message) {
        this(message, null, 0, null);
    }

    /**
     * Creates a new exception with a message and cause.
     *
     * @param message the error message
     * @param cause   the cause
     */
    public LlmProviderException(String message, Throwable cause) {
        this(message, cause, 0, null);
    }

    /**
     * Creates a new exception with full details.
     *
     * @param message    the error message
     * @param cause      the cause
     * @param statusCode the HTTP status code (if applicable)
     * @param errorType  the error type from the API
     */
    public LlmProviderException(String message, Throwable cause, int statusCode, String errorType) {
        this(message, cause, statusCode, errorType, null);
    }

    /**
     * Creates a new exception with full details including error code.
     *
     * @param message    the error message
     * @param cause      the cause
     * @param statusCode the HTTP status code (if applicable)
     * @param errorType  the error type from the API (e.g. "rate_limit_exceeded")
     * @param errorCode  the specific error code from the API (e.g. "spend_window_5h_exceeded")
     */
    public LlmProviderException(String message, Throwable cause, int statusCode, String errorType, String errorCode) {
        super(message, cause);
        this.statusCode = statusCode;
        this.errorType = errorType;
        this.errorCode = errorCode;
    }

    /**
     * Returns the HTTP status code, or 0 if not applicable.
     *
     * @return the status code
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * Returns the error type from the API, or null if not available.
     *
     * @return the error type
     */
    public String getErrorType() {
        return errorType;
    }

    /**
     * Checks if this is a rate limit error.
     *
     * @return true if rate limited
     */
    public boolean isRateLimitError() {
        return statusCode == 429 || "rate_limit_error".equals(errorType); //$NON-NLS-1$
    }

    /**
     * Returns the specific error code from the API, or null if not available.
     * Examples: "spend_window_5h_exceeded", "spend_window_7d_exceeded", "budget_exhausted".
     *
     * @return the error code
     */
    public String getErrorCode() {
        return errorCode;
    }

    /**
     * Checks if this is an authentication error.
     *
     * @return true if authentication failed
     */
    public boolean isAuthenticationError() {
        return statusCode == 401 || statusCode == 403 || "authentication_error".equals(errorType); //$NON-NLS-1$
    }

    /**
     * Checks if this is a spending window limit error (5h or 7d).
     *
     * @return true if a spending window was exceeded
     */
    public boolean isSpendWindowError() {
        return errorCode != null && errorCode.startsWith("spend_window_"); //$NON-NLS-1$
    }

    /**
     * Checks if this is a budget exhaustion error (total budget used up).
     *
     * @return true if the budget is exhausted
     */
    public boolean isBudgetExhausted() {
        return "budget_exhausted".equals(errorCode); //$NON-NLS-1$
    }

    /**
     * Returns the rate limit details, or null if not a rate limit error.
     */
    public RateLimitDetails getRateLimitDetails() {
        return rateLimitDetails;
    }

    /**
     * Sets the rate limit details parsed from the API response.
     */
    public void setRateLimitDetails(RateLimitDetails details) {
        this.rateLimitDetails = details;
    }

    /**
     * Structured details from a rate-limit / budget error response.
     *
     * @param limitCents      the spending limit in cents
     * @param usedCents       how much was already used
     * @param attemptedCents  the cost of the denied request
     * @param window          the window label (e.g. "5h", "7d")
     * @param retryAtLocal    human-readable local retry time (e.g. "03.04.2026 16:43:34 MSK")
     * @param retryAfterSeconds seconds until the limit resets
     */
    public record RateLimitDetails(long limitCents, long usedCents, long attemptedCents,
            String window, String retryAtLocal, long retryAfterSeconds) {

        /**
         * Returns remaining budget in cents.
         */
        public long remainingCents() {
            return Math.max(0, limitCents - usedCents);
        }

        /**
         * Returns usage percentage (0-100).
         */
        public int usagePercent() {
            return limitCents > 0 ? (int) (usedCents * 100 / limitCents) : 100;
        }

        /**
         * Returns retry wait as human-readable string (e.g. "~2 ч 01 мин").
         */
        public String retryWaitFormatted() {
            if (retryAfterSeconds <= 0) {
                return ""; //$NON-NLS-1$
            }
            long hours = retryAfterSeconds / 3600;
            long minutes = (retryAfterSeconds % 3600) / 60;
            if (hours > 0) {
                return String.format("~%d \u0447 %02d \u043C\u0438\u043D", hours, minutes); //$NON-NLS-1$
            }
            return String.format("~%d \u043C\u0438\u043D", minutes); //$NON-NLS-1$
        }
    }
}
