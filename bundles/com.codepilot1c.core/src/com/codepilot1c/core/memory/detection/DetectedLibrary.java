/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory.detection;

/**
 * A detected 1C standard library in the project configuration.
 *
 * <p>Detection is based on presence of known common modules, subsystems,
 * and constants in the configuration metadata. Actual library version
 * (stored in infobase constants) cannot be read from EDT metadata;
 * {@link #versionHint()} is best-effort from synonym/comment fields.</p>
 *
 * @param id          short identifier: "bsp", "bip", "bed", "bpo", "bdo"
 * @param name        short Russian name: "БСП", "БИП", "БЭД", "БПО", "БДО"
 * @param fullName    full Russian name
 * @param versionHint best-effort version hint from metadata (synonym/comment), may be null
 * @param confidence  detection confidence 0.0-1.0
 */
public record DetectedLibrary(
        String id,
        String name,
        String fullName,
        String versionHint,
        double confidence
) {
}
