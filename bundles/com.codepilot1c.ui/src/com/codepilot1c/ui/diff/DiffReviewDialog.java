/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import com.codepilot1c.core.diff.InlineDiffUtils;
import com.codepilot1c.core.diff.LineDiffUtils;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;

/**
 * Dialog for reviewing proposed changes before applying.
 *
 * <p>Shows a list of changes with diff view and Accept/Reject controls.</p>
 */
public class DiffReviewDialog extends Dialog {

    private final ProposedChangeSet changeSet;
    private final BooleanSupplier applyAuthorization;

    private TableViewer changeListViewer;
    private StyledText beforeText;
    private StyledText afterText;
    private Label statusLabel;
    private Button acceptButton;
    private Button rejectButton;

    private Font codeFont;
    private Color addedColor;
    private Color removedColor;
    private Color placeholderColor;
    private Color headerColor;
    // Darker colors for inline (word-level) highlighting
    private Color inlineAddedColor;
    private Color inlineRemovedColor;

    private boolean isSynchronizingScroll;

    /**
     * Creates a new diff review dialog.
     *
     * @param parentShell the parent shell
     * @param changeSet the proposed change set to review
     */
    public DiffReviewDialog(Shell parentShell, ProposedChangeSet changeSet) {
        this(parentShell, changeSet, () -> true);
    }

    /** Creates a dialog with a final authorization check before any accepted change is applied. */
    public DiffReviewDialog(
            Shell parentShell, ProposedChangeSet changeSet,
            BooleanSupplier applyAuthorization) {
        super(parentShell);
        this.changeSet = changeSet;
        this.applyAuthorization = Objects.requireNonNull(
                applyAuthorization, "applyAuthorization"); //$NON-NLS-1$
        setShellStyle(getShellStyle() | SWT.RESIZE | SWT.MAX);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Просмотр изменений"); //$NON-NLS-1$
        shell.setSize(900, 600);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite container = (Composite) super.createDialogArea(parent);
        container.setLayout(new GridLayout(1, false));

        Display display = parent.getDisplay();
        codeFont = new Font(display, "Menlo", 11, SWT.NORMAL); //$NON-NLS-1$
        addedColor = new Color(display, 220, 255, 220);
        removedColor = new Color(display, 255, 220, 220);
        placeholderColor = new Color(display, 240, 240, 240);
        headerColor = new Color(display, 240, 240, 245);
        // Darker colors for inline word-level highlighting (like VS Code)
        inlineAddedColor = new Color(display, 155, 230, 155);
        inlineRemovedColor = new Color(display, 230, 155, 155);

        // Header with summary
        createHeader(container);

        // Main content: file list and diff view
        SashForm sash = new SashForm(container, SWT.HORIZONTAL);
        sash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // Left: file list with status
        createFileList(sash);

        // Right: diff view
        createDiffView(sash);

        sash.setWeights(new int[] { 30, 70 });

        // Per-change action buttons
        createChangeActions(container);

        // Select first change
        if (!changeSet.isEmpty()) {
            changeListViewer.getTable().select(0);
            showSelectedChange();
        }

        return container;
    }

    private void createHeader(Composite parent) {
        Composite header = new Composite(parent, SWT.NONE);
        header.setLayout(new GridLayout(2, false));
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        header.setBackground(headerColor);

        Label titleLabel = new Label(header, SWT.NONE);
        titleLabel.setText(String.format("Предложенные изменения: %d файлов", changeSet.size())); //$NON-NLS-1$
        titleLabel.setBackground(headerColor);
        titleLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        statusLabel = new Label(header, SWT.NONE);
        statusLabel.setBackground(headerColor);
        updateStatusLabel();
    }

    private void createFileList(Composite parent) {
        Composite listContainer = new Composite(parent, SWT.NONE);
        listContainer.setLayout(new GridLayout(1, false));

        Label listLabel = new Label(listContainer, SWT.NONE);
        listLabel.setText("Файлы:"); //$NON-NLS-1$

        changeListViewer = new TableViewer(listContainer, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
        Table table = changeListViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // Status column
        TableViewerColumn statusCol = new TableViewerColumn(changeListViewer, SWT.NONE);
        statusCol.getColumn().setText(""); //$NON-NLS-1$
        statusCol.getColumn().setWidth(30);
        statusCol.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                ProposedChange change = (ProposedChange) element;
                return switch (change.getStatus()) {
                    case PENDING -> "\u25CB"; // ○ //$NON-NLS-1$
                    case ACCEPTED -> "\u2713"; // ✓ //$NON-NLS-1$
                    case REJECTED -> "\u2717"; // ✗ //$NON-NLS-1$
                    case APPLIED -> "\u2714"; // ✔ //$NON-NLS-1$
                    case FAILED -> "\u26A0"; // ⚠ //$NON-NLS-1$
                };
            }
        });

        // File name column
        TableViewerColumn fileCol = new TableViewerColumn(changeListViewer, SWT.NONE);
        fileCol.getColumn().setText("Файл"); //$NON-NLS-1$
        fileCol.getColumn().setWidth(200);
        fileCol.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                ProposedChange change = (ProposedChange) element;
                return change.getFileName();
            }
        });

        // Action column
        TableViewerColumn actionCol = new TableViewerColumn(changeListViewer, SWT.NONE);
        actionCol.getColumn().setText("Действие"); //$NON-NLS-1$
        actionCol.getColumn().setWidth(80);
        actionCol.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                ProposedChange change = (ProposedChange) element;
                return switch (change.getKind()) {
                    case CREATE -> "Создать"; //$NON-NLS-1$
                    case MODIFY -> "Изменить"; //$NON-NLS-1$
                    case REPLACE -> "Заменить"; //$NON-NLS-1$
                    case DELETE -> "Удалить"; //$NON-NLS-1$
                };
            }
        });

        changeListViewer.setContentProvider(ArrayContentProvider.getInstance());
        changeListViewer.setInput(changeSet.getChanges());

        changeListViewer.addSelectionChangedListener(new ISelectionChangedListener() {
            @Override
            public void selectionChanged(SelectionChangedEvent event) {
                showSelectedChange();
            }
        });
    }

    private void createDiffView(Composite parent) {
        Composite diffContainer = new Composite(parent, SWT.NONE);
        diffContainer.setLayout(new GridLayout(1, false));

        // Side-by-side diff
        SashForm diffSash = new SashForm(diffContainer, SWT.HORIZONTAL);
        diffSash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // Before (original)
        Composite beforeContainer = new Composite(diffSash, SWT.NONE);
        beforeContainer.setLayout(new GridLayout(1, false));

        Label beforeLabel = new Label(beforeContainer, SWT.NONE);
        beforeLabel.setText("До:"); //$NON-NLS-1$

        beforeText = new StyledText(beforeContainer, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
        beforeText.setFont(codeFont);
        beforeText.setEditable(false);
        beforeText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // After (proposed)
        Composite afterContainer = new Composite(diffSash, SWT.NONE);
        afterContainer.setLayout(new GridLayout(1, false));

        Label afterLabel = new Label(afterContainer, SWT.NONE);
        afterLabel.setText("После:"); //$NON-NLS-1$

        afterText = new StyledText(afterContainer, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
        afterText.setFont(codeFont);
        afterText.setEditable(false);
        afterText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // Синхронная прокрутка панелей (Aligned side-by-side diff)
        ScrollBar beforeVBar = beforeText.getVerticalBar();
        ScrollBar afterVBar = afterText.getVerticalBar();
        if (beforeVBar != null && afterVBar != null) {
            beforeVBar.addListener(SWT.Selection, e -> syncScroll(beforeText, afterText));
            afterVBar.addListener(SWT.Selection, e -> syncScroll(afterText, beforeText));
        }

        diffSash.setWeights(new int[] { 50, 50 });
    }

    private void syncScroll(StyledText source, StyledText target) {
        if (isSynchronizingScroll) {
            return;
        }
        isSynchronizingScroll = true;
        try {
            int topIndex = source.getTopIndex();
            int maxTargetIndex = Math.max(0, target.getLineCount() - 1);
            target.setTopIndex(Math.min(topIndex, maxTargetIndex));
        } finally {
            isSynchronizingScroll = false;
        }
    }

    private void createChangeActions(Composite parent) {
        Composite actionBar = new Composite(parent, SWT.NONE);
        actionBar.setLayout(new GridLayout(4, false));
        actionBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        acceptButton = new Button(actionBar, SWT.PUSH);
        acceptButton.setText("Принять"); //$NON-NLS-1$
        acceptButton.addListener(SWT.Selection, e -> acceptSelected());

        rejectButton = new Button(actionBar, SWT.PUSH);
        rejectButton.setText("Отклонить"); //$NON-NLS-1$
        rejectButton.addListener(SWT.Selection, e -> rejectSelected());

        // Spacer
        Label spacer = new Label(actionBar, SWT.NONE);
        spacer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button acceptAllButton = new Button(actionBar, SWT.PUSH);
        acceptAllButton.setText("Принять все"); //$NON-NLS-1$
        acceptAllButton.addListener(SWT.Selection, e -> acceptAll());

        Button rejectAllButton = new Button(actionBar, SWT.PUSH);
        rejectAllButton.setText("Отклонить все"); //$NON-NLS-1$
        rejectAllButton.addListener(SWT.Selection, e -> rejectAll());
    }

    private void showSelectedChange() {
        IStructuredSelection selection = changeListViewer.getStructuredSelection();
        if (selection.isEmpty()) {
            beforeText.setText(""); //$NON-NLS-1$
            afterText.setText(""); //$NON-NLS-1$
            beforeText.setBackground(null);
            afterText.setBackground(null);
            clearLineBackgrounds(beforeText);
            clearLineBackgrounds(afterText);
            updateButtonState(null);
            return;
        }

        ProposedChange change = (ProposedChange) selection.getFirstElement();

        String before = change.getBeforeContent();
        String after = change.getAfterContent();

        // Важно: сохраняем существующую логику для новых/удалённых файлов.
        if (before == null || after == null) {
            // Show before content
            beforeText.setText(before != null ? before : "(новый файл)"); //$NON-NLS-1$
            if (before == null) {
                beforeText.setBackground(removedColor);
            } else {
                beforeText.setBackground(null);
            }
            clearLineBackgrounds(beforeText);

            // Show after content
            afterText.setText(after != null ? after : "(удалён)"); //$NON-NLS-1$
            if (after == null) {
                afterText.setBackground(removedColor);
            } else if (before == null) {
                afterText.setBackground(addedColor);
            } else {
                afterText.setBackground(null);
            }
            clearLineBackgrounds(afterText);

            updateButtonState(change);
            return;
        }

        // Aligned side-by-side diff по строкам с контекстом ±3 строки.
        LineDiffUtils.DiffResult diff = LineDiffUtils.computeDiff(before, after);
        List<LineDiffUtils.AlignedRow> alignedRows = diff.getAlignedRowsWithContext(3);

        StringBuilder beforeBuilder = new StringBuilder();
        StringBuilder afterBuilder = new StringBuilder();

        // Track line start offsets for inline highlighting
        List<Integer> beforeLineOffsets = new ArrayList<>();
        List<Integer> afterLineOffsets = new ArrayList<>();
        // Store actual content strings for each line (for inline diff)
        List<String> beforeLineContents = new ArrayList<>();
        List<String> afterLineContents = new ArrayList<>();

        for (int i = 0; i < alignedRows.size(); i++) {
            LineDiffUtils.AlignedRow row = alignedRows.get(i);

            if (i > 0) {
                beforeBuilder.append('\n');
                afterBuilder.append('\n');
            }

            // Record offset before appending line content
            beforeLineOffsets.add(beforeBuilder.length());
            afterLineOffsets.add(afterBuilder.length());

            // Для "пустых" ячеек (строки отсутствуют слева/справа) показываем пустую строку.
            // Исключение: разделитель "..." (оба номера -1) оставляем, чтобы было видно разрыв контекста.
            boolean isSeparator = row.getLeftLineNumber() < 0
                    && row.getRightLineNumber() < 0
                    && "...".equals(row.getLeftContent()) //$NON-NLS-1$
                    && "...".equals(row.getRightContent()); //$NON-NLS-1$

            String left = row.getLeftLineNumber() < 0 && !isSeparator ? "" : row.getLeftContent(); //$NON-NLS-1$
            String right = row.getRightLineNumber() < 0 && !isSeparator ? "" : row.getRightContent(); //$NON-NLS-1$
            left = left != null ? left : ""; //$NON-NLS-1$
            right = right != null ? right : ""; //$NON-NLS-1$

            beforeBuilder.append(left);
            afterBuilder.append(right);
            beforeLineContents.add(left);
            afterLineContents.add(right);
        }

        beforeText.setText(beforeBuilder.toString());
        afterText.setText(afterBuilder.toString());

        // Общий фон сбрасываем — дальше используем построчную подсветку.
        beforeText.setBackground(null);
        afterText.setBackground(null);

        // Подсветка строк после setText()
        applyLineHighlights(alignedRows);

        // Apply inline (word-level) highlighting for MODIFIED rows
        applyInlineHighlights(alignedRows, beforeLineOffsets, afterLineOffsets,
            beforeLineContents, afterLineContents);

        updateButtonState(change);
    }

    private void applyLineHighlights(List<LineDiffUtils.AlignedRow> alignedRows) {
        // Сначала очищаем подсветку, чтобы не оставались артефакты от предыдущего файла.
        clearLineBackgrounds(beforeText);
        clearLineBackgrounds(afterText);

        int rowCount = alignedRows.size();
        for (int lineIndex = 0; lineIndex < rowCount; lineIndex++) {
            LineDiffUtils.AlignedRow row = alignedRows.get(lineIndex);

            boolean leftPlaceholder = row.getLeftLineNumber() < 0;
            boolean rightPlaceholder = row.getRightLineNumber() < 0;

            // Заглушки подсвечиваем серым фоном.
            if (leftPlaceholder) {
                beforeText.setLineBackground(lineIndex, 1, placeholderColor);
            }
            if (rightPlaceholder) {
                afterText.setLineBackground(lineIndex, 1, placeholderColor);
            }

            // Подсветка изменений.
            switch (row.getType()) {
                case DELETED:
                    beforeText.setLineBackground(lineIndex, 1, removedColor);
                    break;
                case ADDED:
                    afterText.setLineBackground(lineIndex, 1, addedColor);
                    break;
                case MODIFIED:
                    beforeText.setLineBackground(lineIndex, 1, removedColor);
                    afterText.setLineBackground(lineIndex, 1, addedColor);
                    break;
                case UNCHANGED:
                    // Ничего не делаем, если это не заглушка (заглушки уже покрашены выше).
                    break;
            }
        }
    }

    /**
     * Applies inline (word-level) highlighting for MODIFIED rows.
     * This highlights exactly which characters changed within a line, like VS Code diff.
     */
    private void applyInlineHighlights(List<LineDiffUtils.AlignedRow> alignedRows,
            List<Integer> beforeLineOffsets, List<Integer> afterLineOffsets,
            List<String> beforeLineContents, List<String> afterLineContents) {

        // Clear previous style ranges
        beforeText.replaceStyleRanges(0, beforeText.getCharCount(), new StyleRange[0]);
        afterText.replaceStyleRanges(0, afterText.getCharCount(), new StyleRange[0]);

        List<StyleRange> beforeRanges = new ArrayList<>();
        List<StyleRange> afterRanges = new ArrayList<>();

        for (int i = 0; i < alignedRows.size(); i++) {
            LineDiffUtils.AlignedRow row = alignedRows.get(i);

            // Only compute inline diff for MODIFIED rows
            if (row.getType() != LineDiffUtils.AlignedRow.RowType.MODIFIED) {
                continue;
            }

            String leftContent = beforeLineContents.get(i);
            String rightContent = afterLineContents.get(i);
            int beforeOffset = beforeLineOffsets.get(i);
            int afterOffset = afterLineOffsets.get(i);

            // Skip if either side is empty (shouldn't happen for MODIFIED, but be safe)
            if (leftContent.isEmpty() && rightContent.isEmpty()) {
                continue;
            }

            // Compute inline diff
            InlineDiffUtils.InlineDiffResult inlineDiff =
                InlineDiffUtils.diff(leftContent, rightContent);

            // Apply highlights for left (removed chars)
            for (InlineDiffUtils.HighlightRange range : inlineDiff.getLeftRanges()) {
                int start = beforeOffset + range.getStart();
                int length = range.getLength();
                // Ensure we don't exceed text bounds
                if (start >= 0 && start + length <= beforeText.getCharCount()) {
                    StyleRange styleRange = new StyleRange();
                    styleRange.start = start;
                    styleRange.length = length;
                    styleRange.background = inlineRemovedColor;
                    beforeRanges.add(styleRange);
                }
            }

            // Apply highlights for right (added chars)
            for (InlineDiffUtils.HighlightRange range : inlineDiff.getRightRanges()) {
                int start = afterOffset + range.getStart();
                int length = range.getLength();
                // Ensure we don't exceed text bounds
                if (start >= 0 && start + length <= afterText.getCharCount()) {
                    StyleRange styleRange = new StyleRange();
                    styleRange.start = start;
                    styleRange.length = length;
                    styleRange.background = inlineAddedColor;
                    afterRanges.add(styleRange);
                }
            }
        }

        // Apply all ranges at once for better performance
        if (!beforeRanges.isEmpty()) {
            beforeText.setStyleRanges(beforeRanges.toArray(new StyleRange[0]));
        }
        if (!afterRanges.isEmpty()) {
            afterText.setStyleRanges(afterRanges.toArray(new StyleRange[0]));
        }
    }

    private void clearLineBackgrounds(StyledText text) {
        int lineCount = text.getLineCount();
        for (int i = 0; i < lineCount; i++) {
            text.setLineBackground(i, 1, null);
        }
    }

    private void updateButtonState(ProposedChange change) {
        boolean canModify = change != null && change.isPending();
        acceptButton.setEnabled(canModify);
        rejectButton.setEnabled(canModify);
    }

    private void acceptSelected() {
        IStructuredSelection selection = changeListViewer.getStructuredSelection();
        if (!selection.isEmpty()) {
            ProposedChange change = (ProposedChange) selection.getFirstElement();
            change.accept();
            changeListViewer.refresh(change);
            updateStatusLabel();
            updateButtonState(change);
            selectNext();
        }
    }

    private void rejectSelected() {
        IStructuredSelection selection = changeListViewer.getStructuredSelection();
        if (!selection.isEmpty()) {
            ProposedChange change = (ProposedChange) selection.getFirstElement();
            change.reject();
            changeListViewer.refresh(change);
            updateStatusLabel();
            updateButtonState(change);
            selectNext();
        }
    }

    private void selectNext() {
        // Find next pending change
        List<ProposedChange> changes = changeSet.getChanges();
        int currentIndex = changeListViewer.getTable().getSelectionIndex();

        for (int i = currentIndex + 1; i < changes.size(); i++) {
            if (changes.get(i).isPending()) {
                changeListViewer.getTable().select(i);
                showSelectedChange();
                return;
            }
        }

        // Wrap around
        for (int i = 0; i < currentIndex; i++) {
            if (changes.get(i).isPending()) {
                changeListViewer.getTable().select(i);
                showSelectedChange();
                return;
            }
        }
    }

    private void acceptAll() {
        changeSet.acceptAll();
        changeListViewer.refresh();
        updateStatusLabel();
        showSelectedChange();
    }

    private void rejectAll() {
        changeSet.rejectAll();
        changeListViewer.refresh();
        updateStatusLabel();
        showSelectedChange();
    }

    private void updateStatusLabel() {
        int pending = changeSet.getPendingCount();
        int accepted = changeSet.getAcceptedCount();
        int rejected = changeSet.getRejectedCount();

        statusLabel.setText(String.format("Ожидает: %d | Принято: %d | Отклонено: %d", //$NON-NLS-1$
                pending, accepted, rejected));
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, "Применить принятые", true); //$NON-NLS-1$
        createButton(parent, IDialogConstants.CANCEL_ID, "Отмена", false); //$NON-NLS-1$
    }

    @Override
    protected void okPressed() {
        // Check if there are accepted changes
        if (!changeSet.hasAcceptedChanges()) {
            // Nothing to apply
            super.cancelPressed();
            return;
        }

        if (!applyAuthorization.getAsBoolean()) {
            for (ProposedChange change : changeSet.getAcceptedChanges()) {
                change.markFailed();
            }
            super.okPressed();
            return;
        }

        // Apply accepted changes
        ChangeApplicator applicator = ChangeApplicator.getInstance();
        ChangeApplicator.ApplyResult result = applicator.applyAcceptedChanges(changeSet);

        if (!result.isFullySuccessful()) {
            // Show errors but still close
            // Could show a message dialog here
        }

        super.okPressed();
    }

    @Override
    public boolean close() {
        // Dispose resources
        if (codeFont != null && !codeFont.isDisposed()) {
            codeFont.dispose();
        }
        if (addedColor != null && !addedColor.isDisposed()) {
            addedColor.dispose();
        }
        if (removedColor != null && !removedColor.isDisposed()) {
            removedColor.dispose();
        }
        if (placeholderColor != null && !placeholderColor.isDisposed()) {
            placeholderColor.dispose();
        }
        if (headerColor != null && !headerColor.isDisposed()) {
            headerColor.dispose();
        }
        if (inlineAddedColor != null && !inlineAddedColor.isDisposed()) {
            inlineAddedColor.dispose();
        }
        if (inlineRemovedColor != null && !inlineRemovedColor.isDisposed()) {
            inlineRemovedColor.dispose();
        }
        return super.close();
    }

    /**
     * Opens the dialog and returns whether changes were applied.
     *
     * @return true if changes were applied
     */
    public boolean openAndApply() {
        return open() == IDialogConstants.OK_ID;
    }
}
