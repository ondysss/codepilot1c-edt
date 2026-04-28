/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.profiling;

import java.util.Map;

/**
 * Request to toggle EDT runtime profiling for a debug target.
 */
public record StartProfilingRequest(String applicationId) {

    public static StartProfilingRequest fromParameters(Map<String, Object> parameters) {
        Object value = parameters == null ? null : parameters.get("applicationId"); //$NON-NLS-1$
        if (value == null && parameters != null) {
            value = parameters.get("application_id"); //$NON-NLS-1$
        }
        String applicationId = value == null ? null : String.valueOf(value).trim();
        return new StartProfilingRequest(applicationId == null || applicationId.isBlank() ? null : applicationId);
    }
}
