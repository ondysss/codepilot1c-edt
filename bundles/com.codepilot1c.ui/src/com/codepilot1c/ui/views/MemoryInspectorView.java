/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.views;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.part.ViewPart;

import com.codepilot1c.core.memory.MemoryCategory;
import com.codepilot1c.core.memory.MemoryEntry;
import com.codepilot1c.core.memory.MemoryQuery;
import com.codepilot1c.core.memory.MemoryService;
import com.codepilot1c.core.memory.extraction.MemoryExtractor;
import com.codepilot1c.core.session.Session;
import com.codepilot1c.core.session.SessionManager;

/**
 * Read-only inspector view that displays the current contents of project memory.
 *
 * <p>Shows memory entries grouped by {@link MemoryCategory} in a tree layout.
 * Provides Refresh and Re-extract actions in the toolbar area.</p>
 *
 * <p>This is Phase 3.3 of the memory subsystem implementation plan.</p>
 */
public class MemoryInspectorView extends ViewPart {

    /** The view ID as registered in plugin.xml. */
    public static final String ID = "com.codepilot1c.ui.views.MemoryInspectorView"; //$NON-NLS-1$

    private static final ILog LOG = Platform.getLog(MemoryInspectorView.class);

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm") //$NON-NLS-1$
                    .withZone(ZoneId.systemDefault());

    /** Display labels for each memory category section. */
    private static final Map<MemoryCategory, String> SECTION_LABELS;
    static {
        SECTION_LABELS = new EnumMap<>(MemoryCategory.class);
        SECTION_LABELS.put(MemoryCategory.PENDING, "\u041D\u0435\u0437\u0430\u043A\u0440\u044B\u0442\u044B\u0435 \u0437\u0430\u0434\u0430\u0447\u0438"); //$NON-NLS-1$
        SECTION_LABELS.put(MemoryCategory.ARCHITECTURE, "\u0410\u0440\u0445\u0438\u0442\u0435\u043A\u0442\u0443\u0440\u043D\u044B\u0435 \u0440\u0435\u0448\u0435\u043D\u0438\u044F"); //$NON-NLS-1$
        SECTION_LABELS.put(MemoryCategory.DECISION, "\u041F\u0440\u043E\u0435\u043A\u0442\u043D\u044B\u0435 \u0440\u0435\u0448\u0435\u043D\u0438\u044F"); //$NON-NLS-1$
        SECTION_LABELS.put(MemoryCategory.PREFERENCE, "\u041F\u0440\u0435\u0434\u043F\u043E\u0447\u0442\u0435\u043D\u0438\u044F"); //$NON-NLS-1$
        SECTION_LABELS.put(MemoryCategory.FACT, "\u0417\u0430\u043C\u0435\u0442\u043A\u0438 \u043F\u0440\u043E\u0435\u043A\u0442\u0430"); //$NON-NLS-1$
        SECTION_LABELS.put(MemoryCategory.BUG, "\u0418\u0437\u0432\u0435\u0441\u0442\u043D\u044B\u0435 \u0431\u0430\u0433\u0438"); //$NON-NLS-1$
        SECTION_LABELS.put(MemoryCategory.PATTERN, "\u0428\u0430\u0431\u043B\u043E\u043D\u044B \u043A\u043E\u0434\u0430"); //$NON-NLS-1$
    }

    private Tree tree;
    private Label statusLabel;

    @Override
    public void createPartControl(Composite parent) {
        Composite container = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        container.setLayout(layout);

        createToolbar(container);
        createTree(container);
        createStatusBar(container);

        refreshContents();
    }

    private void createToolbar(Composite parent) {
        Composite toolbar = new Composite(parent, SWT.NONE);
        GridLayout toolbarLayout = new GridLayout(3, false);
        toolbarLayout.marginHeight = 4;
        toolbarLayout.marginWidth = 4;
        toolbar.setLayout(toolbarLayout);
        toolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button refreshButton = new Button(toolbar, SWT.PUSH);
        refreshButton.setText("\u041E\u0431\u043D\u043E\u0432\u0438\u0442\u044C"); //$NON-NLS-1$
        refreshButton.setToolTipText("\u041F\u0435\u0440\u0435\u0447\u0438\u0442\u0430\u0442\u044C \u0437\u0430\u043F\u0438\u0441\u0438 \u043F\u0430\u043C\u044F\u0442\u0438 \u0441 \u0434\u0438\u0441\u043A\u0430"); //$NON-NLS-1$
        refreshButton.addListener(SWT.Selection, e -> refreshContents());

        Button reextractButton = new Button(toolbar, SWT.PUSH);
        reextractButton.setText("\u041F\u0435\u0440\u0435\u0438\u0437\u0432\u043B\u0435\u0447\u044C"); //$NON-NLS-1$
        reextractButton.setToolTipText("\u0417\u0430\u043F\u0443\u0441\u0442\u0438\u0442\u044C \u0438\u0437\u0432\u043B\u0435\u0447\u0435\u043D\u0438\u0435 \u043F\u0430\u043C\u044F\u0442\u0438 \u0438\u0437 \u0442\u0435\u043A\u0443\u0449\u0435\u0439 \u0441\u0435\u0441\u0441\u0438\u0438"); //$NON-NLS-1$
        reextractButton.addListener(SWT.Selection, e -> reextractFromSession());

        // Spacer
        Label spacer = new Label(toolbar, SWT.NONE);
        spacer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    private void createTree(Composite parent) {
        tree = new Tree(parent, SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.FULL_SELECTION);
        tree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        // Read-only: no editing support
        tree.setEnabled(true);
    }

    private void createStatusBar(Composite parent) {
        statusLabel = new Label(parent, SWT.NONE);
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.horizontalIndent = 4;
        statusLabel.setLayoutData(gd);
        statusLabel.setText(""); //$NON-NLS-1$
    }

    /**
     * Refreshes the tree contents by reading memory entries from the current project.
     */
    private void refreshContents() {
        String projectPath = resolveProjectPath();
        if (projectPath == null) {
            showNoProject();
            return;
        }

        // Run recall on a background job to avoid blocking the UI thread
        Job.create("\u0417\u0430\u0433\u0440\u0443\u0437\u043A\u0430 \u0437\u0430\u043F\u0438\u0441\u0435\u0439 \u043F\u0430\u043C\u044F\u0442\u0438", monitor -> { //$NON-NLS-1$
            List<MemoryEntry> entries = MemoryService.recall(projectPath, MemoryQuery.all());
            Display display = getDisplay();
            if (display != null && !display.isDisposed()) {
                display.asyncExec(() -> populateTree(entries, projectPath));
            }
        }).schedule();
    }

    /**
     * Triggers re-extraction from the current session and then refreshes the view.
     */
    private void reextractFromSession() {
        Session session = SessionManager.getInstance().getCurrentSession();
        if (session == null) {
            setStatusText("\u041D\u0435\u0442 \u0430\u043A\u0442\u0438\u0432\u043D\u043E\u0439 \u0441\u0435\u0441\u0441\u0438\u0438 \u0434\u043B\u044F \u0438\u0437\u0432\u043B\u0435\u0447\u0435\u043D\u0438\u044F"); //$NON-NLS-1$
            return;
        }
        if (session.getProjectPath() == null) {
            setStatusText("\u0422\u0435\u043A\u0443\u0449\u0430\u044F \u0441\u0435\u0441\u0441\u0438\u044F \u043D\u0435 \u043F\u0440\u0438\u0432\u044F\u0437\u0430\u043D\u0430 \u043A \u043F\u0440\u043E\u0435\u043A\u0442\u0443"); //$NON-NLS-1$
            return;
        }

        setStatusText("\u0418\u0437\u0432\u043B\u0435\u0447\u0435\u043D\u0438\u0435 \u043F\u0430\u043C\u044F\u0442\u0438 \u0438\u0437 \u0442\u0435\u043A\u0443\u0449\u0435\u0439 \u0441\u0435\u0441\u0441\u0438\u0438..."); //$NON-NLS-1$
        Job.create("\u041F\u0435\u0440\u0435\u0438\u0437\u0432\u043B\u0435\u0447\u0435\u043D\u0438\u0435 \u043F\u0430\u043C\u044F\u0442\u0438", monitor -> { //$NON-NLS-1$
            try {
                MemoryExtractor.extract(session);
                Display display = getDisplay();
                if (display != null && !display.isDisposed()) {
                    display.asyncExec(() -> {
                        setStatusText("\u0418\u0437\u0432\u043B\u0435\u0447\u0435\u043D\u0438\u0435 \u0437\u0430\u0432\u0435\u0440\u0448\u0435\u043D\u043E"); //$NON-NLS-1$
                        refreshContents();
                    });
                }
            } catch (Exception e) {
                LOG.warn("Memory extraction failed", e); //$NON-NLS-1$
                Display display = getDisplay();
                if (display != null && !display.isDisposed()) {
                    display.asyncExec(() -> setStatusText("\u041E\u0448\u0438\u0431\u043A\u0430 \u0438\u0437\u0432\u043B\u0435\u0447\u0435\u043D\u0438\u044F: " + e.getMessage())); //$NON-NLS-1$
                }
            }
        }).schedule();
    }

    /**
     * Populates the tree widget with memory entries grouped by category.
     */
    private void populateTree(List<MemoryEntry> entries, String projectPath) {
        if (tree == null || tree.isDisposed()) {
            return;
        }
        tree.removeAll();

        if (entries.isEmpty()) {
            TreeItem emptyItem = new TreeItem(tree, SWT.NONE);
            emptyItem.setText("\u0417\u0430\u043F\u0438\u0441\u0438 \u043F\u0430\u043C\u044F\u0442\u0438 \u043D\u0435 \u043D\u0430\u0439\u0434\u0435\u043D\u044B"); //$NON-NLS-1$
            setStatusText("\u041F\u0440\u043E\u0435\u043A\u0442: " + projectPath + " \u2014 0 \u0437\u0430\u043F\u0438\u0441\u0435\u0439"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        // Group entries by category
        Map<MemoryCategory, List<MemoryEntry>> grouped = new EnumMap<>(MemoryCategory.class);
        for (MemoryEntry entry : entries) {
            grouped.computeIfAbsent(entry.getCategory(), k -> new java.util.ArrayList<>()).add(entry);
        }

        // Display sections in a fixed order matching the requirements:
        // Pending Tasks, Architecture Decisions, then all others
        MemoryCategory[] displayOrder = {
            MemoryCategory.PENDING,
            MemoryCategory.ARCHITECTURE,
            MemoryCategory.DECISION,
            MemoryCategory.PREFERENCE,
            MemoryCategory.FACT,
            MemoryCategory.BUG,
            MemoryCategory.PATTERN
        };

        for (MemoryCategory category : displayOrder) {
            List<MemoryEntry> categoryEntries = grouped.get(category);
            if (categoryEntries == null || categoryEntries.isEmpty()) {
                continue;
            }

            String sectionLabel = SECTION_LABELS.getOrDefault(category, category.name());
            TreeItem sectionItem = new TreeItem(tree, SWT.NONE);
            sectionItem.setText(sectionLabel + " (" + categoryEntries.size() + ")"); //$NON-NLS-1$ //$NON-NLS-2$

            for (MemoryEntry entry : categoryEntries) {
                TreeItem entryItem = new TreeItem(sectionItem, SWT.NONE);
                entryItem.setText(formatEntryLabel(entry));
                entryItem.setData(entry);

                // Add content as a child node for expandable detail
                String content = entry.getContent();
                if (content != null && !content.isBlank()) {
                    // Split multi-line content into separate child items
                    String[] lines = content.split("\\R", 20); //$NON-NLS-1$
                    for (String line : lines) {
                        String trimmed = line.strip();
                        if (!trimmed.isEmpty()) {
                            TreeItem contentItem = new TreeItem(entryItem, SWT.NONE);
                            contentItem.setText(trimmed);
                        }
                    }
                }
            }

            sectionItem.setExpanded(true);
        }

        setStatusText("\u041F\u0440\u043E\u0435\u043A\u0442: " + projectPath + " \u2014 " + entries.size() + " \u0437\u0430\u043F\u0438\u0441\u0435\u0439"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Formats a single memory entry for display in the tree.
     */
    private String formatEntryLabel(MemoryEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append(entry.getKey());

        if (entry.getCreatedAt() != null) {
            sb.append("  [").append(DATE_FMT.format(entry.getCreatedAt())).append(']'); //$NON-NLS-1$
        }

        if (entry.isExpired()) {
            sb.append("  (\u0438\u0441\u0442\u0435\u043A\u043B\u043E)"); //$NON-NLS-1$
        }

        return sb.toString();
    }

    /**
     * Displays a placeholder message when no project is bound.
     */
    private void showNoProject() {
        if (tree == null || tree.isDisposed()) {
            return;
        }
        tree.removeAll();
        TreeItem item = new TreeItem(tree, SWT.NONE);
        item.setText("\u041F\u0440\u043E\u0435\u043A\u0442 \u043D\u0435 \u043F\u0440\u0438\u0432\u044F\u0437\u0430\u043D. \u041E\u0442\u043A\u0440\u043E\u0439\u0442\u0435 \u043F\u0440\u043E\u0435\u043A\u0442 \u0438\u043B\u0438 \u043D\u0430\u0447\u043D\u0438\u0442\u0435 \u0441\u0435\u0441\u0441\u0438\u044E."); //$NON-NLS-1$
        setStatusText("\u041D\u0435\u0442 \u043F\u0440\u043E\u0435\u043A\u0442\u0430"); //$NON-NLS-1$
    }

    /**
     * Resolves the project path from the current session or workspace selection.
     *
     * @return the project path, or {@code null} if unavailable
     */
    private String resolveProjectPath() {
        // First, try the current session
        Session session = SessionManager.getInstance().getCurrentSession();
        if (session != null && session.getProjectPath() != null) {
            return session.getProjectPath();
        }

        // Fallback: try to find a project from the workspace
        try {
            org.eclipse.core.resources.IWorkspaceRoot root =
                    org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot();
            org.eclipse.core.resources.IProject[] projects = root.getProjects();
            for (org.eclipse.core.resources.IProject project : projects) {
                if (project.isOpen() && project.getLocation() != null) {
                    return project.getLocation().toOSString();
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to resolve project from workspace", e); //$NON-NLS-1$
        }

        return null;
    }

    private void setStatusText(String text) {
        if (statusLabel != null && !statusLabel.isDisposed()) {
            statusLabel.setText(text);
            statusLabel.getParent().layout(true);
        }
    }

    private Display getDisplay() {
        if (tree != null && !tree.isDisposed()) {
            return tree.getDisplay();
        }
        return null;
    }

    @Override
    public void setFocus() {
        if (tree != null && !tree.isDisposed()) {
            tree.setFocus();
        }
    }
}
