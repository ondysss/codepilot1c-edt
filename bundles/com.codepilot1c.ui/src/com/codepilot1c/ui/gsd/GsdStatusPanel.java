/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.gsd;

import java.nio.file.Path;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;

import com.codepilot1c.core.gsd.GsdFeatureGate;
import com.codepilot1c.core.gsd.GsdStateStore;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.ui.theme.ThemeManager;
import com.codepilot1c.ui.theme.VibeTheme;

/**
 * Reusable, collapsible SWT panel that displays a read-only GSD status snapshot.
 *
 * <p>Shows: phase, goal, revision, active session/workstream, tasks done/total,
 * current wave, evidence count, last load error, suggested profile hint, and a
 * Refresh button.</p>
 *
 * <h2>Threading model</h2>
 * <p>{@link GsdStateStore#loadReadOnly()} runs off the SWT/UI thread via
 * {@link GsdAsyncStateLoader}. The resulting {@link GsdUiSnapshot} is applied to controls only inside
 * {@link Display#asyncExec(Runnable)} with disposed checks. This class never
 * blocks the UI thread on I/O.</p>
 *
 * <h2>Read-only</h2>
 * <p>This panel does NOT write {@code state.json}, does NOT produce Markdown
 * projections, and does NOT invoke any tools. It uses {@code loadReadOnly()}
 * which guarantees zero filesystem writes. It is a pure display component.</p>
 */
public class GsdStatusPanel {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(GsdStatusPanel.class);
    private static final int PANEL_MARGIN = 6;
    private static final int PANEL_COLUMNS = 4;
    private static final int AUTO_REFRESH_DEBOUNCE_MS = 125;
    private static final long LOAD_FAILURE_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final Composite root;
    private final Composite contentArea;
    private final Button collapseToggle;
    private final Button refreshButton;
    private final Display display;

    // Status labels
    private Label phaseLabel;
    private Label goalLabel;
    private Label revisionLabel;
    private Label sessionLabel;
    private Label workstreamLabel;
    private Label tasksLabel;
    private Label waveLabel;
    private Label evidenceLabel;
    private Label profileHintLabel;
    private Label errorLabel;

    private boolean collapsed = false;

    /** Guards against stale async results overwriting fresh data. */
    private final GsdRefreshCoordinator coordinator = new GsdRefreshCoordinator();

    /** Prevents a failing auto-refresh loop from flooding the Eclipse log. */
    private final GsdLoadFailureLogGuard loadFailureLogGuard =
            new GsdLoadFailureLogGuard(LOAD_FAILURE_LOG_INTERVAL_NANOS);

    /** Async boundary whose executor and disk loader are independently testable. */
    private final GsdAsyncStateLoader asyncStateLoader = new GsdAsyncStateLoader(
            ForkJoinPool.commonPool(),
            projectRoot -> new GsdStateStore(projectRoot).loadReadOnly());

    /** Latest automatic refresh request; older timer callbacks become no-ops. */
    private final AtomicLong refreshRequestSequence = new AtomicLong();

    /** Volatile so the constructor-registered click listener always sees the latest value. */
    private volatile GsdUiSnapshot currentSnapshot;

    /** Optional callback when the user clicks the suggested-profile hint. */
    private volatile Consumer<String> suggestedProfileAction;

    /**
     * Creates the panel inside {@code parent}. The caller is responsible for
     * setting layout data on the returned root composite.
     *
     * @param parent the parent composite
     */
    public GsdStatusPanel(Composite parent) {
        VibeTheme theme = ThemeManager.getInstance().getTheme();
        this.currentSnapshot = GsdUiSnapshot.empty();
        this.display = parent.getDisplay();

        root = new Composite(parent, SWT.NONE);
        root.addDisposeListener(e -> {
            refreshRequestSequence.incrementAndGet();
            coordinator.dispose();
        });
        root.setBackground(theme.getSurface());
        GridLayout rootLayout = new GridLayout(1, false);
        rootLayout.marginWidth = 0;
        rootLayout.marginHeight = 0;
        rootLayout.verticalSpacing = 0;
        root.setLayout(rootLayout);

        // Header row: toggle + title + refresh
        Composite header = new Composite(root, SWT.NONE);
        header.setBackground(theme.getSurface());
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout headerLayout = new GridLayout(3, false);
        headerLayout.marginWidth = PANEL_MARGIN;
        headerLayout.marginHeight = 2;
        headerLayout.horizontalSpacing = 6;
        header.setLayout(headerLayout);

        collapseToggle = new Button(header, SWT.TOGGLE);
        collapseToggle.setText("\u25BC"); //$NON-NLS-1$
        collapseToggle.setToolTipText("Collapse/expand GSD status"); //$NON-NLS-1$
        collapseToggle.setBackground(theme.getSurface());
        GridData toggleData = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        toggleData.widthHint = 24;
        toggleData.heightHint = 22;
        collapseToggle.setLayoutData(toggleData);
        collapseToggle.setSelection(true);
        collapseToggle.addListener(SWT.Selection, e -> toggleCollapse());

        Label titleLabel = new Label(header, SWT.NONE);
        titleLabel.setText("GSD Status"); //$NON-NLS-1$
        titleLabel.setBackground(theme.getSurface());
        titleLabel.setForeground(theme.getText());
        titleLabel.setFont(createBoldFont(titleLabel));
        titleLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        refreshButton = new Button(header, SWT.PUSH);
        refreshButton.setText("\u21BB Refresh"); //$NON-NLS-1$
        refreshButton.setToolTipText("Reload GSD state from disk"); //$NON-NLS-1$
        refreshButton.setBackground(theme.getSurface());
        GridData refreshData = new GridData(SWT.RIGHT, SWT.CENTER, false, false);
        refreshData.heightHint = 22;
        refreshButton.setLayoutData(refreshData);
        refreshButton.addListener(SWT.Selection, e -> refresh());

        // Content area (collapsible)
        contentArea = new Composite(root, SWT.NONE);
        contentArea.setBackground(theme.getSurface());
        GridData contentData = new GridData(SWT.FILL, SWT.FILL, true, false);
        contentData.horizontalIndent = PANEL_MARGIN;
        contentArea.setLayoutData(contentData);
        GridLayout contentLayout = new GridLayout(PANEL_COLUMNS, false);
        contentLayout.marginWidth = PANEL_MARGIN;
        contentLayout.marginHeight = 2;
        contentLayout.horizontalSpacing = 8;
        contentLayout.verticalSpacing = 4;
        contentArea.setLayout(contentLayout);

        createContentLabels(contentArea, theme);

        // Error row (hidden by default)
        errorLabel = new Label(contentArea, SWT.WRAP);
        errorLabel.setBackground(theme.getSurface());
        errorLabel.setForeground(theme.getDanger());
        GridData errorData = new GridData(SWT.FILL, SWT.CENTER, true, false, PANEL_COLUMNS, 1);
        errorData.exclude = true;
        errorLabel.setLayoutData(errorData);
        errorLabel.setVisible(false);

        // Register the profile-hint click listener ONCE in the constructor.
        // It reads currentSnapshot at click time, so it always uses the latest value.
        profileHintLabel.addListener(SWT.MouseDown, e -> {
            Consumer<String> action = this.suggestedProfileAction;
            GsdUiSnapshot snap = this.currentSnapshot;
            if (action != null && snap != null && !snap.suggestedProfileId().isEmpty()) {
                action.accept(snap.suggestedProfileId());
            }
        });

        // Initial empty state
        applySnapshot(GsdUiSnapshot.empty());
    }

    private void createContentLabels(Composite parent, VibeTheme theme) {
        // Row 1: Phase + Revision
        addFieldLabel(parent, "Phase:", theme); //$NON-NLS-1$
        phaseLabel = addValueLabel(parent, theme, 1);

        addFieldLabel(parent, "Revision:", theme); //$NON-NLS-1$
        revisionLabel = addValueLabel(parent, theme, 1);

        // Row 2: Goal (spans 3 columns)
        addFieldLabel(parent, "Goal:", theme); //$NON-NLS-1$
        goalLabel = addValueLabel(parent, theme, 3);

        // Row 3: Session + Workstream (separate fields)
        addFieldLabel(parent, "Session:", theme); //$NON-NLS-1$
        sessionLabel = addValueLabel(parent, theme, 1);

        addFieldLabel(parent, "Workstream:", theme); //$NON-NLS-1$
        workstreamLabel = addValueLabel(parent, theme, 1);

        // Row 4: Tasks + Wave
        addFieldLabel(parent, "Tasks:", theme); //$NON-NLS-1$
        tasksLabel = addValueLabel(parent, theme, 1);

        addFieldLabel(parent, "Wave:", theme); //$NON-NLS-1$
        waveLabel = addValueLabel(parent, theme, 1);

        // Row 5: Evidence + Profile hint
        addFieldLabel(parent, "Evidence:", theme); //$NON-NLS-1$
        evidenceLabel = addValueLabel(parent, theme, 1);

        addFieldLabel(parent, "Profile:", theme); //$NON-NLS-1$
        profileHintLabel = addValueLabel(parent, theme, 1);
    }

    private Label addFieldLabel(Composite parent, String text, VibeTheme theme) {
        Label label = new Label(parent, SWT.NONE);
        label.setText(text);
        label.setBackground(theme.getSurface());
        label.setForeground(theme.getTextMuted());
        label.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        return label;
    }

    private Label addValueLabel(Composite parent, VibeTheme theme, int hSpan) {
        Label label = new Label(parent, SWT.NONE);
        label.setText("—"); //$NON-NLS-1$
        label.setBackground(theme.getSurface());
        label.setForeground(theme.getText());
        GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false, hSpan, 1);
        data.widthHint = 50;
        label.setLayoutData(data);
        return label;
    }

    private Font createBoldFont(Label label) {
        Font base = label.getFont();
        org.eclipse.swt.graphics.FontData[] fds = base.getFontData();
        for (org.eclipse.swt.graphics.FontData fd : fds) {
            fd.setStyle(fd.getStyle() | SWT.BOLD);
        }
        Font bold = new Font(label.getDisplay(), fds);
        label.addDisposeListener(e -> bold.dispose());
        return bold;
    }

    // ---- Public API ---------------------------------------------------------

    /**
     * Returns the root composite. The caller manages its layout data and visibility.
     *
     * @return the root composite
     */
    public Composite getControl() {
        return root;
    }

    /**
     * Sets the project root for subsequent {@link #refresh()} calls.
     * Bumps the refresh generation so any in-flight results for the old
     * root are discarded when they arrive.
     *
     * @param projectRoot the project root path; may be {@code null} (disables refresh)
     */
    public void setProjectRoot(Path projectRoot) {
        this.coordinator.setProjectRoot(GsdFeatureGate.getInstance().isEnabled()
                ? projectRoot : null);
    }

    /**
     * Sets an optional action invoked when the user interacts with the suggested-profile hint.
     * The listener is registered once in the constructor; this sets the callback target.
     *
     * @param action callback receiving the suggested profile id
     */
    public void setSuggestedProfileAction(Consumer<String> suggestedProfileAction) {
        this.suggestedProfileAction = suggestedProfileAction;
    }

    /**
     * Triggers an asynchronous reload of the GSD state from disk.
     * Uses {@link GsdStateStore#loadReadOnly()} which guarantees zero filesystem writes.
     * The read runs off the UI thread; the result is applied via {@code Display.asyncExec}
     * only if the refresh token still matches the current coordinator state
     * (stale-result guard: concurrent {@code setProjectRoot} or overlapping
     * refreshes invalidate earlier tokens).
     */
    public void refresh() {
        refreshRequestSequence.incrementAndGet();
        refreshNow();
    }

    /**
     * Requests a debounced automatic refresh. Safe from tool-completion and
     * session-listener threads; all SWT work is marshalled to the UI thread.
     */
    public void refreshDebounced() {
        long request = refreshRequestSequence.incrementAndGet();
        if (display == null || display.isDisposed() || coordinator.isDisposed()) {
            return;
        }
        Runnable schedule = () -> {
            if (root.isDisposed() || coordinator.isDisposed()) {
                return;
            }
            display.timerExec(AUTO_REFRESH_DEBOUNCE_MS, () -> {
                if (request == refreshRequestSequence.get()
                        && !root.isDisposed() && !coordinator.isDisposed()) {
                    refresh();
                }
            });
        };
        if (display.getThread() == Thread.currentThread()) {
            schedule.run();
        } else {
            try {
                display.asyncExec(schedule);
            } catch (org.eclipse.swt.SWTException e) {
                // Display was disposed between the guard and dispatch.
            }
        }
    }

    private void refreshNow() {
        if (!GsdFeatureGate.getInstance().isEnabled()) {
            coordinator.setProjectRoot(null);
            return;
        }
        GsdRefreshCoordinator.RefreshToken token = this.coordinator.beginRefresh();
        if (token == null) {
            if (!coordinator.isDisposed()) {
                applySnapshot(GsdUiSnapshot.error("No project root resolved")); //$NON-NLS-1$
            }
            return;
        }

        if (display == null || display.isDisposed()) {
            return;
        }

        Path projectRoot = token.projectRoot();

        asyncStateLoader.load(projectRoot, this.coordinator, token)
                .thenAccept(result -> {
            if (result.skipped()) {
                return;
            }
            GsdUiSnapshot snapshot;
            if (result.error() != null) {
                logLoadFailure(token, result.error());
                String msg = result.error().getMessage();
                snapshot = GsdUiSnapshot.error(
                        msg != null ? msg : result.error().getClass().getSimpleName());
            } else {
                snapshot = GsdUiSnapshotMapper.toSnapshot(result.state());
            }
            if (!display.isDisposed()) {
                try {
                    display.asyncExec(() -> {
                        if (!root.isDisposed()
                                && this.coordinator.shouldApply(token)) {
                            applySnapshot(snapshot);
                        }
                    });
                } catch (org.eclipse.swt.SWTException e) {
                    // Display was disposed between the guard and dispatch.
                }
            }
        });
    }

    private void logLoadFailure(GsdRefreshCoordinator.RefreshToken token, Exception error) {
        GsdLoadFailureLogGuard.Decision decision =
                loadFailureLogGuard.register(token.projectRoot(), error);
        if (!decision.shouldLog()) {
            return;
        }
        String message = String.format(
                "event=gsd_async_load_failed opId=%s projectRoot=%s errorType=%s suppressedDuplicates=%d", //$NON-NLS-1$
                quoteLogValue("gsd-load-" + token.generation()), //$NON-NLS-1$
                quoteLogValue(token.projectRoot().toString()),
                quoteLogValue(error.getClass().getName()),
                decision.suppressedDuplicates());
        LOG.warn(message, error);
    }

    private static String quoteLogValue(String value) {
        String escaped = value == null ? "" : value //$NON-NLS-1$
                .replace("\\", "\\\\") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("\"", "\\\"") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("\r", "\\r") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("\n", "\\n"); //$NON-NLS-1$ //$NON-NLS-2$
        return '"' + escaped + '"';
    }

    /**
     * Applies a snapshot to the UI controls. Safe to call from any thread —
     * dispatches to the UI thread if needed.
     *
     * @param snapshot the snapshot to display; {@code null} is treated as empty
     */
    public void applySnapshot(GsdUiSnapshot snapshot) {
        if (root.isDisposed() || coordinator.isDisposed()) {
            return;
        }
        final GsdUiSnapshot normalized = (snapshot != null) ? snapshot : GsdUiSnapshot.empty();
        this.currentSnapshot = normalized;

        if (display == null || display.isDisposed()) {
            return;
        }

        // If already on the UI thread, apply directly; otherwise asyncExec
        if (display.getThread() == Thread.currentThread()) {
            applySnapshotOnUiThread(normalized);
        } else {
            try {
                display.asyncExec(() -> applySnapshotOnUiThread(normalized));
            } catch (org.eclipse.swt.SWTException e) {
                // Display was disposed between the guard and dispatch.
            }
        }
    }

    /**
     * Returns the currently displayed snapshot.
     *
     * @return the current snapshot, never {@code null}
     */
    public GsdUiSnapshot getCurrentSnapshot() {
        GsdUiSnapshot snap = this.currentSnapshot;
        return snap != null ? snap : GsdUiSnapshot.empty();
    }

    /**
     * Disposes all resources held by this panel.
     */
    public void dispose() {
        refreshRequestSequence.incrementAndGet();
        coordinator.dispose();
        if (!root.isDisposed()) {
            root.dispose();
        }
    }

    /**
     * Whether the panel's root control is disposed.
     *
     * @return {@code true} if disposed
     */
    public boolean isDisposed() {
        return root.isDisposed();
    }

    // ---- Internals ----------------------------------------------------------

    private void applySnapshotOnUiThread(GsdUiSnapshot snapshot) {
        if (root.isDisposed() || phaseLabel == null || phaseLabel.isDisposed()) {
            return;
        }

        phaseLabel.setText(snapshot.phase());
        goalLabel.setText(truncate(snapshot.goal(), 80));
        revisionLabel.setText(String.valueOf(snapshot.revision()));

        sessionLabel.setText(truncate(snapshot.activeSession(), 32));
        workstreamLabel.setText(truncate(snapshot.activeWorkstream(), 32));

        tasksLabel.setText(snapshot.tasksProgress());
        waveLabel.setText(snapshot.currentWaveName().isEmpty() ? "—" : snapshot.currentWaveName()); //$NON-NLS-1$
        evidenceLabel.setText(String.valueOf(snapshot.evidenceCount()));

        // Profile hint
        String profileId = snapshot.suggestedProfileId();
        if (!profileId.isEmpty()) {
            profileHintLabel.setText(profileId + " (suggested)"); //$NON-NLS-1$
            profileHintLabel.setToolTipText("Click to select this profile for this chat"); //$NON-NLS-1$
        } else {
            profileHintLabel.setText("—"); //$NON-NLS-1$
            profileHintLabel.setToolTipText(null);
        }

        // Error
        boolean hasError = !snapshot.loadError().isEmpty();
        if (hasError) {
            errorLabel.setText("Error: " + snapshot.loadError()); //$NON-NLS-1$
            ((GridData) errorLabel.getLayoutData()).exclude = false;
            errorLabel.setVisible(true);
        } else {
            ((GridData) errorLabel.getLayoutData()).exclude = true;
            errorLabel.setVisible(false);
        }

        root.layout(true, true);
    }

    private String truncate(String text, int maxLen) {
        if (text == null || text.isEmpty()) {
            return "—"; //$NON-NLS-1$
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen - 1) + "\u2026"; //$NON-NLS-1$
    }

    private void toggleCollapse() {
        collapsed = !collapseToggle.getSelection();
        ((GridData) contentArea.getLayoutData()).exclude = collapsed;
        contentArea.setVisible(!collapsed);
        collapseToggle.setText(collapsed ? "\u25B6" : "\u25BC"); //$NON-NLS-1$ //$NON-NLS-2$
        root.layout(true, true);
        // Relayout parent so the ChatView adjusts
        if (root.getParent() != null && !root.getParent().isDisposed()) {
            root.getParent().layout(true, true);
        }
    }

    /**
     * Resolves a project root path from an Eclipse {@link IProject}.
     *
     * @param project the project; may be {@code null}
     * @return the project root path, or {@code null}
     */
    public static Path resolveProjectRoot(IProject project) {
        if (project == null || !project.exists() || !project.isOpen()) {
            return null;
        }
        IPath location = project.getLocation();
        if (location == null) {
            return null;
        }
        try {
            return Path.of(location.toOSString());
        } catch (Exception e) {
            return null;
        }
    }
}
