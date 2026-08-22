/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.gsd;

import com.codepilot1c.core.gsd.GsdFeatureGate;

/** UI projection of the core-owned GSD availability policy. */
public final class GsdUiProfilePolicy {

    /** Safe read-only fallback used when a persisted GSD profile becomes unavailable. */
    public static final String FALLBACK_PROFILE_ID = "explore"; //$NON-NLS-1$

    private GsdUiProfilePolicy() {
    }

    /**
     * Replaces an unavailable persisted GSD profile with the safe read-only
     * fallback. Other profile ids, including {@code null}, are unchanged.
     */
    public static String safeProfileId(String profileId) {
        return GsdFeatureGate.getInstance().isProfileAvailable(profileId)
                ? profileId : FALLBACK_PROFILE_ID;
    }
}
