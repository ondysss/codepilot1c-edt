/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.preferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.codepilot1c.core.skills.SkillDefinition;
import com.codepilot1c.ui.internal.Messages;

/**
 * Dialog for creating or editing a skill definition.
 *
 * <p>For BUNDLED skills, all fields are read-only.</p>
 */
public class SkillEditDialog extends TitleAreaDialog {

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9-]+"); //$NON-NLS-1$
    private static final int MAX_DESCRIPTION_LENGTH = 200;

    private final SkillDefinition existing;
    private final List<SkillDefinition> allSkills;
    private final boolean readOnly;
    private final boolean isEdit;

    private Text nameText;
    private Text descriptionText;
    private Button backendOnlyCheck;
    private Text bodyText;

    // Results
    private String resultName;
    private String resultDescription;
    private boolean resultBackendOnly;
    private String resultBody;
    private List<String> resultAllowedTools = List.of();

    /**
     * Creates the dialog.
     *
     * @param shell     parent shell
     * @param existing  skill to edit (null for new)
     * @param allSkills all discovered skills (for duplicate check)
     */
    public SkillEditDialog(Shell shell, SkillDefinition existing, List<SkillDefinition> allSkills) {
        super(shell);
        this.existing = existing;
        this.allSkills = allSkills != null ? allSkills : List.of();
        this.isEdit = existing != null;
        this.readOnly = existing != null && existing.sourceType() != SkillDefinition.SourceType.USER;
    }

    @Override
    protected void configureShell(Shell newShell) {
        super.configureShell(newShell);
        if (existing == null) {
            newShell.setText(Messages.SkillEditDialog_TitleAdd);
        } else if (readOnly) {
            newShell.setText(Messages.SkillEditDialog_TitleView);
        } else {
            newShell.setText(Messages.SkillEditDialog_TitleEdit);
        }
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);

        if (existing == null) {
            setTitle(Messages.SkillEditDialog_TitleAdd);
        } else if (readOnly) {
            setTitle(Messages.SkillEditDialog_TitleView);
        } else {
            setTitle(Messages.SkillEditDialog_TitleEdit);
        }

        Composite container = new Composite(area, SWT.NONE);
        container.setLayout(new GridLayout(2, false));
        container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // Name
        new Label(container, SWT.NONE).setText(Messages.SkillEditDialog_Name);
        nameText = new Text(container, SWT.BORDER);
        nameText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        if (existing != null) {
            nameText.setText(existing.name());
        }
        // Name is always read-only when editing (rename not supported)
        nameText.setEnabled(!isEdit);

        // Description
        new Label(container, SWT.NONE).setText(Messages.SkillEditDialog_Description);
        descriptionText = new Text(container, SWT.BORDER);
        descriptionText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        descriptionText.setTextLimit(MAX_DESCRIPTION_LENGTH);
        if (existing != null) {
            descriptionText.setText(existing.description());
        }
        descriptionText.setEnabled(!readOnly);

        // Backend-only
        backendOnlyCheck = new Button(container, SWT.CHECK);
        backendOnlyCheck.setText(Messages.SkillEditDialog_BackendOnly);
        GridData checkGd = new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1);
        backendOnlyCheck.setLayoutData(checkGd);
        if (existing != null) {
            backendOnlyCheck.setSelection(existing.backendOnly());
        }
        backendOnlyCheck.setEnabled(!readOnly);

        // Body (instructions)
        Label bodyLabel = new Label(container, SWT.NONE);
        bodyLabel.setText(Messages.SkillEditDialog_Body);
        bodyLabel.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false, 2, 1));

        bodyText = new Text(container, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.WRAP);
        GridData bodyGd = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
        bodyGd.heightHint = 200;
        bodyGd.widthHint = 400;
        bodyText.setLayoutData(bodyGd);
        if (existing != null) {
            bodyText.setText(existing.body());
        }
        bodyText.setEnabled(!readOnly);

        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        if (readOnly) {
            createButton(parent, IDialogConstants.OK_ID, IDialogConstants.OK_LABEL, true);
        } else {
            super.createButtonsForButtonBar(parent);
        }
    }

    @Override
    protected void okPressed() {
        if (!readOnly) {
            String name = nameText.getText().trim();
            String desc = descriptionText.getText().trim();

            // Validate name
            if (name.isEmpty()) {
                setErrorMessage(Messages.SkillEditDialog_NameRequired);
                return;
            }
            if (!NAME_PATTERN.matcher(name).matches()) {
                setErrorMessage(Messages.SkillEditDialog_NameInvalid);
                return;
            }
            // Check duplicate (only for new skills)
            if (existing == null) {
                String normalizedNew = name.toLowerCase(Locale.ROOT);
                Set<String> existingNames = allSkills.stream()
                        .map(s -> s.name().trim().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toSet());
                if (existingNames.contains(normalizedNew)) {
                    setErrorMessage(Messages.SkillEditDialog_NameDuplicate);
                    return;
                }
            }
            // Validate description length
            if (desc.length() > MAX_DESCRIPTION_LENGTH) {
                setErrorMessage(Messages.SkillEditDialog_DescriptionTooLong);
                return;
            }

            resultName = name;
            resultDescription = desc;
            resultBackendOnly = backendOnlyCheck.getSelection();
            resultBody = bodyText.getText();

            // Preserve existing allowed-tools if editing
            if (existing != null) {
                resultAllowedTools = existing.allowedTools();
            }
        }
        super.okPressed();
    }

    public String getSkillName() {
        return resultName;
    }

    public String getSkillDescription() {
        return resultDescription;
    }

    public boolean isBackendOnly() {
        return resultBackendOnly;
    }

    public String getBody() {
        return resultBody;
    }

    public List<String> getAllowedTools() {
        return resultAllowedTools;
    }
}
