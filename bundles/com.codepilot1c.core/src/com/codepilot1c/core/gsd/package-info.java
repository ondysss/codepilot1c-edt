/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
/**
 * Get-Things-Done (GSD) project-level state.
 *
 * <p>The first independent slice of GSD: a typed model of project-level state persisted
 * under {@code <project>/.codepilot1c/gsd}. {@code state.json} is the single source of
 * truth; {@code STATE.md} and {@code PLAN.md} are deterministic projections regenerated
 * from JSON. The store provides cycle/generation/revision concurrency tokens, atomic writes
 * (temp + {@link java.nio.channels.FileChannel#force} + {@link java.nio.file.StandardCopyOption#ATOMIC_MOVE}),
 * schema migration, generation-fenced backup recovery, filesystem confinement, and
 * closure guards for verified evidence, acceptance criteria, and completed shipment.</p>
 *
 * <p>This package is pure core, provider-neutral, and has no UI dependencies.</p>
 */
package com.codepilot1c.core.gsd;
