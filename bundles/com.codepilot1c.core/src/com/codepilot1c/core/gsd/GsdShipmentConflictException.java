/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

/** Raised when a delivery cycle already has a different shipment record. */
public final class GsdShipmentConflictException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public GsdShipmentConflictException(String message) {
        super(message);
    }
}
