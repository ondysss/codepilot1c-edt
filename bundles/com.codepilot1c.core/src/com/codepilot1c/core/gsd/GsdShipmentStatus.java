/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

/** Lifecycle of the shipment/delivery record. */
public enum GsdShipmentStatus {
    /** No shipment has started. */
    PENDING,
    /** Delivery is in progress. */
    IN_PROGRESS,
    /** Delivery completed successfully. */
    COMPLETED,
    /** Delivery was attempted but failed. */
    FAILED
}
