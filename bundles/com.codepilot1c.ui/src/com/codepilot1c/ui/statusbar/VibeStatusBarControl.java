/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.statusbar;

import java.util.EnumMap;
import java.util.Map;

import org.eclipse.jface.action.ContributionItem;
import org.eclipse.jface.action.StatusLineLayoutData;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.codepilot1c.core.state.VibeState;
import com.codepilot1c.core.state.VibeStateService;
import com.codepilot1c.ui.internal.VibeUiPlugin;

/**
 * Status bar contribution showing the current 1C Copilot plugin state.
 *
 * <p>Displays an icon and text in the Eclipse status bar indicating
 * whether the plugin is idle, processing, indexing, etc.</p>
 *
 * <p>Based on patterns from 1C:Workmate ({@code StatusBarControl.java}).</p>
 */
public class VibeStatusBarControl extends ContributionItem
        implements VibeStateService.StateChangeListener {

    /** Contribution item ID */
    public static final String ID = "com.codepilot1c.ui.statusbar"; //$NON-NLS-1$

    private Composite composite;
    private Label iconLabel;
    private Label textLabel;
    private VibeState currentDisplayState;

    /** Image cache to avoid recreating images on every state change */
    private final Map<VibeState, Image> imageCache = new EnumMap<>(VibeState.class);

    public VibeStatusBarControl() {
        super(ID);
    }

    public VibeStatusBarControl(String id) {
        super(id);
    }

    @Override
    public void fill(Composite parent) {
        // Create container
        composite = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(2, false);
        layout.marginHeight = 0;
        layout.marginWidth = 4;
        layout.horizontalSpacing = 4;
        composite.setLayout(layout);

        // Set fixed width for status bar item
        StatusLineLayoutData layoutData = new StatusLineLayoutData();
        layoutData.widthHint = 120;
        composite.setLayoutData(layoutData);

        // Icon label
        iconLabel = new Label(composite, SWT.NONE);
        iconLabel.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, false, false));

        // Text label
        textLabel = new Label(composite, SWT.NONE);
        textLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Add tooltip with full state description
        composite.setToolTipText("1C Copilot Status"); //$NON-NLS-1$

        // Add click listener to show details
        composite.addListener(SWT.MouseDown, e -> showStatusDetails());
        iconLabel.addListener(SWT.MouseDown, e -> showStatusDetails());
        textLabel.addListener(SWT.MouseDown, e -> showStatusDetails());

        // Register as listener
        VibeStateService.getInstance().addListener(this);

        // Set initial state
        updateDisplay(VibeStateService.getInstance().getState(),
                VibeStateService.getInstance().getStatusMessage());
    }

    @Override
    public void onStateChanged(VibeState oldState, VibeState newState, String message) {
        // Update on UI thread
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed()) {
            display.asyncExec(() -> {
                if (!isDisposed()) {
                    updateDisplay(newState, message);
                }
            });
        }
    }

    /**
     * Updates the display with the current state.
     */
    private void updateDisplay(VibeState state, String message) {
        if (isDisposed()) {
            return;
        }

        // Update icon only if state changed (uses cached images)
        if (state != currentDisplayState) {
            Image icon = getIconForState(state);
            iconLabel.setImage(icon);
            currentDisplayState = state;
        }

        // Update text
        String displayText = truncateText(message, 15);
        textLabel.setText(displayText);

        // Update tooltip
        String tooltip = buildTooltip(state, message);
        composite.setToolTipText(tooltip);
        iconLabel.setToolTipText(tooltip);
        textLabel.setToolTipText(tooltip);

        // Update colors based on state
        if (state.isError()) {
            textLabel.setForeground(composite.getDisplay().getSystemColor(SWT.COLOR_RED));
        } else if (state.isActive()) {
            textLabel.setForeground(composite.getDisplay().getSystemColor(SWT.COLOR_BLUE));
        } else {
            textLabel.setForeground(null); // Default color
        }

        composite.layout(true);
    }

    /**
     * Gets the icon for the given state (cached to avoid resource leaks).
     * Falls back to Eclipse shared images if custom icons are not found.
     */
    private Image getIconForState(VibeState state) {
        // Check cache first
        Image cached = imageCache.get(state);
        if (cached != null && !cached.isDisposed()) {
            return cached;
        }

        // Try to load custom icon
        String iconPath = state.getIconPath();
        try {
            Image icon = VibeUiPlugin.getImageDescriptor(iconPath).createImage();
            if (icon != null) {
                imageCache.put(state, icon);
                return icon;
            }
        } catch (Exception e) {
            // Fall through to use shared images
        }

        // Fallback to Eclipse shared images (not cached since they're managed by Eclipse)
        ISharedImages sharedImages = PlatformUI.getWorkbench().getSharedImages();
        switch (state) {
            case ERROR:
                return sharedImages.getImage(ISharedImages.IMG_OBJS_ERROR_TSK);
            case NOT_CONFIGURED:
                return sharedImages.getImage(ISharedImages.IMG_OBJS_WARN_TSK);
            case INDEXING:
            case COMPLETING:
            case PROCESSING:
            case STREAMING:
                return sharedImages.getImage(ISharedImages.IMG_ELCL_SYNCED);
            case DISABLED:
                return sharedImages.getImage(ISharedImages.IMG_ELCL_STOP);
            case IDLE:
            default:
                return sharedImages.getImage(ISharedImages.IMG_OBJS_INFO_TSK);
        }
    }

    /**
     * Builds a tooltip with full state information.
     */
    private String buildTooltip(VibeState state, String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("1C Copilot: ").append(state.getDisplayName()); //$NON-NLS-1$

        if (message != null && !message.isEmpty() && !message.equals(state.getDisplayName())) {
            sb.append("\n").append(message); //$NON-NLS-1$
        }

        String error = VibeStateService.getInstance().getErrorMessage();
        if (error != null && !error.isEmpty()) {
            sb.append("\n\nError: ").append(error); //$NON-NLS-1$
        }

        sb.append("\n\nClick for details"); //$NON-NLS-1$
        return sb.toString();
    }

    /**
     * Truncates text to fit in the status bar.
     */
    private String truncateText(String text, int maxLength) {
        if (text == null) {
            return ""; //$NON-NLS-1$
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "..."; //$NON-NLS-1$
    }

    /**
     * Shows status details when clicked.
     */
    private void showStatusDetails() {
        VibeState state = VibeStateService.getInstance().getState();
        String message = VibeStateService.getInstance().getStatusMessage();
        String error = VibeStateService.getInstance().getErrorMessage();

        StringBuilder details = new StringBuilder();
        details.append("Status: ").append(state.getDisplayName()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        details.append("Message: ").append(message).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$

        if (error != null) {
            details.append("Error: ").append(error).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Could open a dialog or view here - for now just log
        VibeUiPlugin.log("Vibe Status: " + details.toString()); //$NON-NLS-1$

        // If not configured, open preferences
        if (state == VibeState.NOT_CONFIGURED) {
            openPreferences();
        }
    }

    /**
     * Opens the 1C Copilot preferences page.
     */
    private void openPreferences() {
        try {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window != null) {
                org.eclipse.ui.dialogs.PreferencesUtil.createPreferenceDialogOn(
                        window.getShell(),
                        "com.codepilot1c.ui.preferences.AccountPreferencePage", //$NON-NLS-1$
                        null, null).open();
            }
        } catch (Exception e) {
            VibeUiPlugin.log(e);
        }
    }

    /**
     * Returns whether this control has been disposed.
     */
    private boolean isDisposed() {
        return composite == null || composite.isDisposed();
    }

    @Override
    public void dispose() {
        // Unregister listener
        VibeStateService.getInstance().removeListener(this);

        // Dispose all cached images to avoid resource leaks
        for (Image image : imageCache.values()) {
            if (image != null && !image.isDisposed()) {
                image.dispose();
            }
        }
        imageCache.clear();
        currentDisplayState = null;

        super.dispose();
    }
}
