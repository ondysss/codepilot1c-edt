/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

/**
 * Prompt context contributor pipeline.
 *
 * <p>Contributors implement {@link com.codepilot1c.core.memory.prompt.IPromptContextContributor}
 * and are invoked in priority order to inject contextual sections into the system prompt.
 * Each contributor receives a {@link com.codepilot1c.core.memory.prompt.PromptAssemblyContext}
 * with remaining token budget and produces a {@link com.codepilot1c.core.memory.prompt.PromptSection}.</p>
 */
package com.codepilot1c.core.memory.prompt;
