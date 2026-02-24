/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.state;

/**
 * Represents the global state of the 1C Copilot plugin.
 *
 * <p>Used for status bar display and service coordination.
 * Based on patterns from 1C:Workmate ({@code StatusBarControl.java}).</p>
 */
public enum VibeState {

    /**
     * Plugin is idle and ready for requests.
     */
    IDLE("idle", "Ready", "icons/vibe_ready.png"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /**
     * A completion request is in progress.
     */
    COMPLETING("completing", "Completing...", "icons/vibe_working.png"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /**
     * A chat request is being processed.
     */
    PROCESSING("processing", "Processing...", "icons/vibe_working.png"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /**
     * Streaming response is being received.
     */
    STREAMING("streaming", "Streaming...", "icons/vibe_streaming.png"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /**
     * Background indexing operation is in progress.
     */
    INDEXING("indexing", "Indexing...", "icons/vibe_indexing.png"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /**
     * An error occurred in the last operation.
     */
    ERROR("error", "Error", "icons/vibe_error.png"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /**
     * Plugin is disabled.
     */
    DISABLED("disabled", "Disabled", "icons/vibe_disabled.png"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /**
     * Plugin is not configured (missing API key, etc.).
     */
    NOT_CONFIGURED("not_configured", "Not Configured", "icons/vibe_warning.png"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private final String id;
    private final String displayName;
    private final String iconPath;

    VibeState(String id, String displayName, String iconPath) {
        this.id = id;
        this.displayName = displayName;
        this.iconPath = iconPath;
    }

    /**
     * Returns the state identifier.
     *
     * @return the id
     */
    public String getId() {
        return id;
    }

    /**
     * Returns the display name for UI.
     *
     * @return the display name
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Returns the relative path to the status icon.
     *
     * @return the icon path
     */
    public String getIconPath() {
        return iconPath;
    }

    /**
     * Returns whether this state represents an active operation.
     *
     * @return true if active
     */
    public boolean isActive() {
        return this == COMPLETING || this == PROCESSING || this == STREAMING || this == INDEXING;
    }

    /**
     * Returns whether this state represents an error condition.
     *
     * @return true if error
     */
    public boolean isError() {
        return this == ERROR || this == NOT_CONFIGURED;
    }

    /**
     * Returns whether the plugin is ready for user requests.
     *
     * @return true if ready
     */
    public boolean isReady() {
        return this == IDLE;
    }

    /**
     * Finds a state by its ID.
     *
     * @param id the id
     * @return the state, or IDLE if not found
     */
    public static VibeState fromId(String id) {
        if (id == null) {
            return IDLE;
        }
        for (VibeState state : values()) {
            if (state.id.equals(id)) {
                return state;
            }
        }
        return IDLE;
    }
}
