/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.preferences;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.osgi.service.prefs.BackingStoreException;

import com.codepilot1c.core.settings.PromptCatalog;
import com.codepilot1c.core.settings.VibePreferenceConstants;

/**
 * Preference page for configuring custom system prompt and command templates.
 */
public class PromptsPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private static final String CORE_PLUGIN_ID = "com.codepilot1c.core"; //$NON-NLS-1$
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{[a-zA-Z0-9_]+\\}\\}"); //$NON-NLS-1$
    private static final List<String> ALL_PLACEHOLDERS = List.of(
            "prompt", "code", "query", "description", "context", "request", "file", "module", "surrounding_code"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$

    private static final List<PromptFieldSpec> FIELD_SPECS = List.of(
            PromptFieldSpec.system(
                    VibePreferenceConstants.PREF_PROMPT_SYSTEM_PREFIX,
                    "Префикс системного промпта", //$NON-NLS-1$
                    "Пример: Ты архитектор 1С (BSL, SDBL, управляемые формы). Сначала формализуй границы, затем предлагай минимально инвазивные изменения."), //$NON-NLS-1$
            PromptFieldSpec.system(
                    VibePreferenceConstants.PREF_PROMPT_SYSTEM_SUFFIX,
                    "Суффикс системного промпта", //$NON-NLS-1$
                    "Пример: Формат ответа: Архитектурное решение, Компромиссы, Риски и ограничения, План внедрения, План проверки."), //$NON-NLS-1$
            PromptFieldSpec.template(
                    VibePreferenceConstants.PREF_PROMPT_TEMPLATE_EXPLAIN_CODE,
                    "Объяснить код", //$NON-NLS-1$
                    "Пример: {{prompt}}\nВыдели слой (предметная область/приложение/инфраструктура/интерфейс), поток данных, точки отказа и 2 варианта улучшения."), //$NON-NLS-1$
            PromptFieldSpec.template(
                    VibePreferenceConstants.PREF_PROMPT_TEMPLATE_GENERATE_CODE,
                    "Сгенерировать код", //$NON-NLS-1$
                    "Пример: По задаче {{description}} сначала укажи допущения и границы ответственности, затем дай боевой код и план проверки."), //$NON-NLS-1$
            PromptFieldSpec.template(
                    VibePreferenceConstants.PREF_PROMPT_TEMPLATE_FIX_CODE,
                    "Исправить код", //$NON-NLS-1$
                    "Пример: Для кода {{code}} дай уровень критичности проблем, минимальное исправление и список отложенных архитектурных рефакторингов."), //$NON-NLS-1$
            PromptFieldSpec.template(
                    VibePreferenceConstants.PREF_PROMPT_TEMPLATE_CRITICISE_CODE,
                    "Проверить код", //$NON-NLS-1$
                    "Пример: Для {{code}} оцени ответственность, транзакции, конкурентность, тестопригодность и предложи приоритетные улучшения."), //$NON-NLS-1$
            PromptFieldSpec.template(
                    VibePreferenceConstants.PREF_PROMPT_TEMPLATE_ADD_CODE,
                    "Добавить код", //$NON-NLS-1$
                    "Пример: Контекст {{context}}, запрос {{request}}. Верни только вставляемый фрагмент без нарушения текущих границ модуля."), //$NON-NLS-1$
            PromptFieldSpec.template(
                    VibePreferenceConstants.PREF_PROMPT_TEMPLATE_DOC_COMMENTS,
                    "Сгенерировать комментарии", //$NON-NLS-1$
                    "Пример: Для {{code}} сгенерируй комментарий с акцентом на ответственность, контракт и ограничения."), //$NON-NLS-1$
            PromptFieldSpec.template(
                    VibePreferenceConstants.PREF_PROMPT_TEMPLATE_OPTIMIZE_QUERY,
                    "Оптимизировать запрос", //$NON-NLS-1$
                    "Пример: Для {{query}} укажи кардинальность/индексы, оптимизированный вариант, риски семантики и план профилирования.") //$NON-NLS-1$
    );

    private final Map<String, String> draftValues = new LinkedHashMap<>();

    private org.eclipse.swt.widgets.List navigationList;
    private Label titleLabel;
    private Label hintLabel;
    private Composite chipsContainer;
    private Text editor;
    private StyledText exampleView;
    private Label statusLabel;
    private Button insertDefaultButton;
    private Button resetButton;
    private Button validateButton;
    private Button copyButton;
    private PromptFieldSpec selectedField;

    public PromptsPreferencePage() {
        setDescription("Настройка пользовательских промптов. Пустое значение = встроенный шаблон."); //$NON-NLS-1$
    }

    @Override
    public void init(IWorkbench workbench) {
        // No-op
    }

    @Override
    protected Control createContents(Composite parent) {
        ScrolledComposite scroll = new ScrolledComposite(parent, SWT.V_SCROLL | SWT.H_SCROLL);
        scroll.setExpandHorizontal(true);
        scroll.setExpandVertical(true);
        scroll.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        for (PromptFieldSpec spec : FIELD_SPECS) {
            draftValues.put(spec.key(), getPreferences().get(spec.key(), "")); //$NON-NLS-1$
        }

        Composite root = new Composite(scroll, SWT.NONE);
        root.setLayout(new GridLayout(1, false));
        createMasterDetail(root);

        scroll.setContent(root);
        scroll.setMinSize(root.computeSize(SWT.DEFAULT, SWT.DEFAULT));
        return scroll;
    }

    private void createMasterDetail(Composite parent) {
        SashForm sash = new SashForm(parent, SWT.HORIZONTAL);
        sash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Composite master = new Composite(sash, SWT.NONE);
        master.setLayout(new GridLayout(1, false));

        Label masterTitle = new Label(master, SWT.NONE);
        masterTitle.setText("Шаблоны"); //$NON-NLS-1$

        navigationList = new org.eclipse.swt.widgets.List(master, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL);
        navigationList.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        for (PromptFieldSpec spec : FIELD_SPECS) {
            navigationList.add(spec.label());
        }
        navigationList.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                int index = navigationList.getSelectionIndex();
                if (index >= 0 && index < FIELD_SPECS.size()) {
                    bindField(FIELD_SPECS.get(index));
                }
            }
        });

        Composite detail = new Composite(sash, SWT.NONE);
        detail.setLayout(new GridLayout(1, false));
        createDetailEditor(detail);

        sash.setWeights(23, 77);
        navigationList.select(0);
        bindField(FIELD_SPECS.get(0));
    }

    private void createDetailEditor(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Редактор"); //$NON-NLS-1$
        group.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        group.setLayout(new GridLayout(1, false));

        titleLabel = new Label(group, SWT.NONE);
        titleLabel.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT));
        titleLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        hintLabel = new Label(group, SWT.WRAP);
        hintLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Group chipsGroup = new Group(group, SWT.NONE);
        chipsGroup.setText("Плейсхолдеры (клик для вставки)"); //$NON-NLS-1$
        chipsGroup.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        chipsGroup.setLayout(new GridLayout(1, false));

        chipsContainer = new Composite(chipsGroup, SWT.NONE);
        RowLayout rowLayout = new RowLayout();
        rowLayout.spacing = 6;
        rowLayout.marginLeft = 0;
        rowLayout.marginTop = 0;
        rowLayout.marginBottom = 0;
        rowLayout.marginRight = 0;
        chipsContainer.setLayout(rowLayout);
        chipsContainer.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Composite actions = new Composite(group, SWT.NONE);
        actions.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        RowLayout actionsLayout = new RowLayout();
        actionsLayout.spacing = 8;
        actionsLayout.marginLeft = 0;
        actionsLayout.marginTop = 0;
        actionsLayout.marginBottom = 0;
        actionsLayout.marginRight = 0;
        actions.setLayout(actionsLayout);

        insertDefaultButton = new Button(actions, SWT.PUSH);
        insertDefaultButton.setText("Вставить дефолт"); //$NON-NLS-1$
        insertDefaultButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                if (selectedField == null || !selectedField.template()) {
                    return;
                }
                editor.setText(PromptCatalog.getDefaultTemplate(selectedField.key()));
            }
        });

        resetButton = new Button(actions, SWT.PUSH);
        resetButton.setText("Сбросить"); //$NON-NLS-1$
        resetButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                editor.setText(""); //$NON-NLS-1$
            }
        });

        validateButton = new Button(actions, SWT.PUSH);
        validateButton.setText("Проверить"); //$NON-NLS-1$
        validateButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                refreshValidationStatus(true);
            }
        });

        copyButton = new Button(actions, SWT.PUSH);
        copyButton.setText("Копировать"); //$NON-NLS-1$
        copyButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                Clipboard clipboard = new Clipboard(getShell().getDisplay());
                try {
                    clipboard.setContents(
                            new Object[] { editor.getText() },
                            new Transfer[] { TextTransfer.getInstance() });
                } finally {
                    clipboard.dispose();
                }
            }
        });

        editor = new Text(group, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        GridData editorData = new GridData(SWT.FILL, SWT.TOP, true, false);
        editorData.heightHint = 160;
        editor.setLayoutData(editorData);
        editor.addModifyListener(e -> {
            if (selectedField == null) {
                return;
            }
            draftValues.put(selectedField.key(), editor.getText());
            refreshValidationStatus(false);
        });

        statusLabel = new Label(group, SWT.WRAP);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Label exampleTitle = new Label(group, SWT.NONE);
        exampleTitle.setText("Пример"); //$NON-NLS-1$

        exampleView = new StyledText(group, SWT.BORDER | SWT.READ_ONLY | SWT.WRAP | SWT.MULTI);
        exampleView.setFont(JFaceResources.getTextFont());
        exampleView.setEditable(false);
        GridData exampleData = new GridData(SWT.FILL, SWT.FILL, true, true);
        exampleData.heightHint = 120;
        exampleView.setLayoutData(exampleData);
    }

    private void bindField(PromptFieldSpec spec) {
        selectedField = spec;
        titleLabel.setText(spec.label());
        hintLabel.setText(buildHintText(spec));

        GridData editorData = (GridData) editor.getLayoutData();
        editorData.heightHint = spec.template() ? 180 : 120;
        editor.setLayoutData(editorData);
        editor.setText(draftValues.getOrDefault(spec.key(), "")); //$NON-NLS-1$

        renderPlaceholderChips(spec);
        renderExample(spec.example());
        updateActionButtons(spec);
        refreshValidationStatus(false);
        editor.getParent().layout(true, true);
    }

    private void renderPlaceholderChips(PromptFieldSpec spec) {
        for (Control child : chipsContainer.getChildren()) {
            child.dispose();
        }

        if (!spec.template()) {
            Label info = new Label(chipsContainer, SWT.NONE);
            info.setText("Для system prompt используйте произвольный текст."); //$NON-NLS-1$
            chipsContainer.layout(true, true);
            return;
        }

        Set<String> required = PromptCatalog.getRequiredPlaceholders(spec.key());
        for (String placeholder : ALL_PLACEHOLDERS) {
            String token = "{{" + placeholder + "}}"; //$NON-NLS-1$ //$NON-NLS-2$
            Button chip = new Button(chipsContainer, SWT.PUSH);
            chip.setText(required.contains(placeholder) ? token + " *" : token); //$NON-NLS-1$
            if (required.contains(placeholder)) {
                chip.setToolTipText("Обязательный плейсхолдер"); //$NON-NLS-1$
                chip.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT));
            }
            chip.addSelectionListener(new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    insertToken(token);
                }
            });
        }
        chipsContainer.layout(true, true);
    }

    private void insertToken(String token) {
        if (editor == null || editor.isDisposed()) {
            return;
        }
        int caret = editor.getCaretPosition();
        editor.insert(token);
        editor.setSelection(caret + token.length());
        editor.setFocus();
    }

    private void renderExample(String text) {
        if (exampleView == null || exampleView.isDisposed()) {
            return;
        }
        String value = text != null ? text : ""; //$NON-NLS-1$
        exampleView.setText(value);
        applyCodeFenceHighlight(exampleView);
        applyPlaceholderHighlight(exampleView, value);

        GridData gd = (GridData) exampleView.getLayoutData();
        gd.heightHint = estimateExampleHeight(text);
        exampleView.setLayoutData(gd);
        exampleView.getParent().layout(true, true);
    }

    private void updateActionButtons(PromptFieldSpec spec) {
        boolean isTemplate = spec.template();
        insertDefaultButton.setEnabled(isTemplate);
        validateButton.setEnabled(isTemplate);
        copyButton.setEnabled(true);
        resetButton.setEnabled(true);
    }

    private String buildHintText(PromptFieldSpec spec) {
        if (!spec.template()) {
            return "Пустое значение = не добавлять пользовательский текст. Поле применяется ко всем системным промптам."; //$NON-NLS-1$
        }
        Set<String> required = PromptCatalog.getRequiredPlaceholders(spec.key());
        if (required.isEmpty()) {
            return "Пустое значение = встроенный шаблон."; //$NON-NLS-1$
        }
        return "Пустое значение = встроенный шаблон. Обязательные плейсхолдеры: " //$NON-NLS-1$
                + required.stream().sorted().map(p -> "{{" + p + "}}").toList(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private int estimateExampleHeight(String text) {
        int lines = 1;
        if (text != null && !text.isEmpty()) {
            lines = Math.max(1, text.split("\\R", -1).length); //$NON-NLS-1$
        }
        return Math.max(44, (lines * 18) + 12);
    }

    private void applyPlaceholderHighlight(StyledText target, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        while (matcher.find()) {
            StyleRange sr = new StyleRange();
            sr.start = matcher.start();
            sr.length = matcher.end() - matcher.start();
            sr.fontStyle = SWT.BOLD;
            sr.foreground = target.getDisplay().getSystemColor(SWT.COLOR_LINK_FOREGROUND);
            target.setStyleRange(sr);
        }
    }

    private void applyCodeFenceHighlight(StyledText target) {
        int lineCount = target.getLineCount();
        if (lineCount <= 0) {
            return;
        }

        boolean inCodeBlock = false;
        for (int i = 0; i < lineCount; i++) {
            String line = target.getLine(i);
            String trimmed = line.stripLeading();
            boolean isFence = trimmed.startsWith("```"); //$NON-NLS-1$

            if (isFence) {
                inCodeBlock = !inCodeBlock;
                target.setLineBackground(i, 1, target.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));

                StyleRange fenceStyle = new StyleRange();
                fenceStyle.start = target.getOffsetAtLine(i);
                fenceStyle.length = line.length();
                fenceStyle.fontStyle = SWT.ITALIC;
                fenceStyle.foreground = target.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY);
                target.setStyleRange(fenceStyle);
                continue;
            }

            if (inCodeBlock) {
                target.setLineBackground(i, 1, target.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
            }
        }
    }

    @Override
    public boolean performOk() {
        setErrorMessage(null);
        IEclipsePreferences prefs = getPreferences();
        for (PromptFieldSpec spec : FIELD_SPECS) {
            String value = draftValues.getOrDefault(spec.key(), ""); //$NON-NLS-1$
            ValidationResult validation = validateField(spec, value);
            if (!validation.valid()) {
                setErrorMessage("[" + spec.label() + "] " + validation.message()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                navigationList.select(FIELD_SPECS.indexOf(spec));
                bindField(spec);
                return false;
            }
            prefs.put(spec.key(), value);
        }

        try {
            prefs.flush();
        } catch (BackingStoreException e) {
            setErrorMessage("Не удалось сохранить настройки промптов: " + e.getMessage()); //$NON-NLS-1$
            return false;
        }

        return super.performOk();
    }

    @Override
    protected void performDefaults() {
        for (PromptFieldSpec spec : FIELD_SPECS) {
            draftValues.put(spec.key(), ""); //$NON-NLS-1$
        }
        if (selectedField != null) {
            bindField(selectedField);
        }
        setErrorMessage(null);
        super.performDefaults();
    }

    private void refreshValidationStatus(boolean strict) {
        if (selectedField == null) {
            return;
        }
        String value = editor.getText();
        ValidationResult validation = validateField(selectedField, value);
        statusLabel.setText(validation.message());
        statusLabel.setForeground(getShell().getDisplay().getSystemColor(
                validation.valid() ? SWT.COLOR_DARK_GREEN : SWT.COLOR_RED));

        if (!validation.valid() && strict) {
            setErrorMessage(validation.message());
        } else {
            setErrorMessage(null);
        }
    }

    private ValidationResult validateField(PromptFieldSpec spec, String value) {
        if (value == null || value.trim().isEmpty()) {
            return ValidationResult.ok("Пусто — будет использован встроенный шаблон/поведение."); //$NON-NLS-1$
        }
        if (!spec.template()) {
            return ValidationResult.ok("Пользовательский system prompt включен."); //$NON-NLS-1$
        }

        Set<String> required = PromptCatalog.getRequiredPlaceholders(spec.key());
        if (required.isEmpty()) {
            return ValidationResult.ok("Шаблон валиден."); //$NON-NLS-1$
        }

        List<String> missing = required.stream()
                .filter(placeholder -> !value.contains("{{" + placeholder + "}}")) //$NON-NLS-1$ //$NON-NLS-2$
                .sorted()
                .toList();

        if (missing.isEmpty()) {
            return ValidationResult.ok("Шаблон валиден."); //$NON-NLS-1$
        }

        return ValidationResult.error("Не хватает обязательных плейсхолдеров: " + missing); //$NON-NLS-1$
    }

    private IEclipsePreferences getPreferences() {
        return InstanceScope.INSTANCE.getNode(CORE_PLUGIN_ID);
    }

    private record ValidationResult(boolean valid, String message) {
        static ValidationResult ok(String message) {
            return new ValidationResult(true, message);
        }

        static ValidationResult error(String message) {
            return new ValidationResult(false, message);
        }
    }

    private record PromptFieldSpec(String key, String label, String example, boolean template) {
        static PromptFieldSpec system(String key, String label, String example) {
            return new PromptFieldSpec(key, label, example, false);
        }

        static PromptFieldSpec template(String key, String label, String example) {
            return new PromptFieldSpec(key, label, example, true);
        }
    }
}
