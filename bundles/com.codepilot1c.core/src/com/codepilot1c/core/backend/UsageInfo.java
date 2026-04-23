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
 */
public class UsageInfo {

    private double spend;
    private double maxBudget;
    private long totalTokens;
    private long promptTokens;
    private long completionTokens;
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

    public long getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(long promptTokens) {
        this.promptTokens = promptTokens;
    }

    public long getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(long completionTokens) {
        this.completionTokens = completionTokens;
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
}
