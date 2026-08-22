/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

/**
 * Persisted acceptance criterion for a delivery cycle.
 *
 * @param id          stable criterion identifier
 * @param description human-readable condition
 * @param required    whether this criterion gates closure
 * @param status      current evaluation result
 */
public record GsdAcceptanceCriterion(
        String id,
        String description,
        boolean required,
        GsdAcceptanceStatus status) {

    public GsdAcceptanceCriterion {
        id = id == null ? "" : id; //$NON-NLS-1$
        description = description == null ? "" : description; //$NON-NLS-1$
        status = status == null ? GsdAcceptanceStatus.PENDING : status;
    }

    /** Creates an unevaluated criterion. */
    public GsdAcceptanceCriterion(String id, String description, boolean required) {
        this(id, description, required, GsdAcceptanceStatus.PENDING);
    }

    /** @return whether this criterion currently passes */
    public boolean passed() {
        return status == GsdAcceptanceStatus.PASSED;
    }

    /** Returns a copy with a new evaluation result. */
    public GsdAcceptanceCriterion withStatus(GsdAcceptanceStatus newStatus) {
        return new GsdAcceptanceCriterion(id, description, required, newStatus);
    }
}
