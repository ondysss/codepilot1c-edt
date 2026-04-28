/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.backend;

/**
 * Usage and budget information returned by the backend.
 *
 * <p>The backend's {@code /api/v1/usage} endpoint exposes an expanded token
 * breakdown (input total/cached/uncached, output, cache-read/cache-creation).
 * Legacy fields ({@code promptTokens} and {@code completionTokens}) are kept
 * as aliases to preserve binary compatibility with older UI paths. The
 * {@link #getPromptTokens()} and {@link #getCompletionTokens()} accessors
 * prefer the new fields when populated and fall back to legacy values
 * otherwise.</p>
 */
public class UsageInfo {

    private double spend;
    private double maxBudget;
    private long totalTokens;
    private long promptTokens;
    private long completionTokens;
    private long inputTokensTotal;
    private long inputTokensUncached;
    private long inputTokensCached;
    private long outputTokens;
    private long cacheReadInputTokens;
    private long cacheCreationInputTokens;
    private String budgetDuration;
    private String resetDate;

    public double getSpend() {
        return spend;
    }

    public void setSpend(double spend) {
        this.spend = spend;
    }

    public double getMaxBudget() {
        return maxBudget;
    }

    public void setMaxBudget(double maxBudget) {
        this.maxBudget = maxBudget;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(long totalTokens) {
        this.totalTokens = totalTokens;
    }

    /**
     * Returns total input tokens. Prefers the expanded
     * {@link #getInputTokensTotal()} when positive, otherwise falls back to the
     * legacy {@code promptTokens} field so existing callers keep working when
     * the backend responds with the old schema.
     */
    public long getPromptTokens() {
        return inputTokensTotal > 0 ? inputTokensTotal : promptTokens;
    }

    public void setPromptTokens(long promptTokens) {
        this.promptTokens = promptTokens;
    }

    /**
     * Returns output tokens. Prefers the expanded {@link #getOutputTokens()}
     * when positive, otherwise falls back to the legacy {@code completionTokens}
     * field.
     */
    public long getCompletionTokens() {
        return outputTokens > 0 ? outputTokens : completionTokens;
    }

    public void setCompletionTokens(long completionTokens) {
        this.completionTokens = completionTokens;
    }

    public long getInputTokensTotal() {
        return inputTokensTotal;
    }

    public void setInputTokensTotal(long inputTokensTotal) {
        this.inputTokensTotal = inputTokensTotal;
    }

    public long getInputTokensUncached() {
        return inputTokensUncached;
    }

    public void setInputTokensUncached(long inputTokensUncached) {
        this.inputTokensUncached = inputTokensUncached;
    }

    public long getInputTokensCached() {
        return inputTokensCached;
    }

    public void setInputTokensCached(long inputTokensCached) {
        this.inputTokensCached = inputTokensCached;
    }

    public long getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(long outputTokens) {
        this.outputTokens = outputTokens;
    }

    public long getCacheReadInputTokens() {
        return cacheReadInputTokens;
    }

    public void setCacheReadInputTokens(long cacheReadInputTokens) {
        this.cacheReadInputTokens = cacheReadInputTokens;
    }

    public long getCacheCreationInputTokens() {
        return cacheCreationInputTokens;
    }

    public void setCacheCreationInputTokens(long cacheCreationInputTokens) {
        this.cacheCreationInputTokens = cacheCreationInputTokens;
    }

    public String getBudgetDuration() {
        return budgetDuration;
    }

    public void setBudgetDuration(String budgetDuration) {
        this.budgetDuration = budgetDuration;
    }

    public String getResetDate() {
        return resetDate;
    }

    public void setResetDate(String resetDate) {
        this.resetDate = resetDate;
    }

    public double getRemaining() {
        return maxBudget - spend;
    }

    public double getUsagePercent() {
        return maxBudget > 0 ? (spend / maxBudget) * 100.0 : 0.0;
    }

    @Override
    public String toString() {
        return "UsageInfo{" //$NON-NLS-1$
                + "spend=" + spend //$NON-NLS-1$
                + ", maxBudget=" + maxBudget //$NON-NLS-1$
                + ", totalTokens=" + totalTokens //$NON-NLS-1$
                + ", inputTokensTotal=" + inputTokensTotal //$NON-NLS-1$
                + ", inputTokensUncached=" + inputTokensUncached //$NON-NLS-1$
                + ", inputTokensCached=" + inputTokensCached //$NON-NLS-1$
                + ", outputTokens=" + outputTokens //$NON-NLS-1$
                + ", cacheReadInputTokens=" + cacheReadInputTokens //$NON-NLS-1$
                + ", cacheCreationInputTokens=" + cacheCreationInputTokens //$NON-NLS-1$
                + ", legacyPromptTokens=" + promptTokens //$NON-NLS-1$
                + ", legacyCompletionTokens=" + completionTokens //$NON-NLS-1$
                + ", budgetDuration=" + budgetDuration //$NON-NLS-1$
                + ", resetDate=" + resetDate //$NON-NLS-1$
                + '}';
    }
}
