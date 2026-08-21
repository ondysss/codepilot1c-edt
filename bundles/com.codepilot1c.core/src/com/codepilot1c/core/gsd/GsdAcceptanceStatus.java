/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

/** Result of evaluating a persisted acceptance criterion. */
public enum GsdAcceptanceStatus {
    /** Criterion has not been evaluated. */
    PENDING,
    /** Criterion was evaluated successfully. */
    PASSED,
    /** Criterion was evaluated and did not pass. */
    FAILED
}
