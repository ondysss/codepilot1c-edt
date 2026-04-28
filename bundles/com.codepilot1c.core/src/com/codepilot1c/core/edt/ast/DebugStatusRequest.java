/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.ast;

import java.util.Map;

public record DebugStatusRequest(String projectName) {

    public static DebugStatusRequest fromParameters(Map<String, Object> parameters) {
        return new DebugStatusRequest(DebugRequestSupport.string(parameters, "projectName")); //$NON-NLS-1$
    }

    public void validate() {
        // projectName is optional; omitted means all active debug targets.
    }
}
