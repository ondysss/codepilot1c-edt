/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

import java.time.Instant;

/**
 * Shipment/delivery record for the current cycle.
 *
 * @param id                shipment identifier
 * @param deliveryReference external delivery/build/release reference
 * @param status            shipment status
 * @param completedAt       completion time; required for {@link GsdShipmentStatus#COMPLETED}
 */
public record GsdShipment(
        String id,
        String deliveryReference,
        GsdShipmentStatus status,
        Instant completedAt) {

    public GsdShipment {
        id = id == null ? "" : id; //$NON-NLS-1$
        deliveryReference = deliveryReference == null ? "" : deliveryReference; //$NON-NLS-1$
        status = status == null ? GsdShipmentStatus.PENDING : status;
    }

    /** @return an empty, not-yet-started shipment record */
    public static GsdShipment empty() {
        return new GsdShipment("", "", GsdShipmentStatus.PENDING, null); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Creates a completed delivery record. */
    public static GsdShipment completed(String id, String deliveryReference, Instant completedAt) {
        return new GsdShipment(id, deliveryReference, GsdShipmentStatus.COMPLETED, completedAt);
    }

    /** @return whether the record represents a completed, timestamped shipment */
    public boolean completed() {
        return status == GsdShipmentStatus.COMPLETED && completedAt != null;
    }

    /** @return whether no shipment has been recorded yet */
    public boolean emptyRecord() {
        return id.isBlank() && deliveryReference.isBlank()
                && status == GsdShipmentStatus.PENDING && completedAt == null;
    }
}
