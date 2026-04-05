/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

/**
 * Persistent memory subsystem for CodePilot1C.
 *
 * <p>Provides multi-layer contextual memory that enriches the system prompt
 * with project metadata, platform knowledge, and user-curated notes.</p>
 *
 * <p>Key packages:</p>
 * <ul>
 *   <li>{@code memory.prompt} - Contributor pipeline for system prompt injection</li>
 *   <li>{@code memory.detection} - EDT-native project metadata detection</li>
 * </ul>
 */
package com.codepilot1c.core.memory;
