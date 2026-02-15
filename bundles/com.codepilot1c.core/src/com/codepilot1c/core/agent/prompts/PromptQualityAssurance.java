/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.prompts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;

/**
 * Легковесная QA-валидация системных промптов.
 *
 * <p>Используется при сборке шаблонов, чтобы предотвратить деградацию
 * структуры инструкций после рефакторинга.</p>
 */
public final class PromptQualityAssurance {

    private static final String PLUGIN_ID = "com.codepilot1c.core"; //$NON-NLS-1$
    private static final ILog LOG = Platform.getLog(PromptQualityAssurance.class);

    private static final String PROP_QA_STRICT = "codepilot1c.prompt.qa.strict"; //$NON-NLS-1$
    private static final int MIN_PROMPT_LENGTH = 80;
    private static final int MAX_PROMPT_LENGTH = 24_000;

    private PromptQualityAssurance() {
        // Utility class.
    }

    /**
     * Проверяет качество шаблона системного промпта.
     *
     * @param templateId ID шаблона
     * @param prompt текст промпта
     * @param requiredSections список обязательных секций (подстроки)
     * @return исходный промпт без изменений
     */
    public static String verify(String templateId, String prompt, List<String> requiredSections) {
        String id = templateId == null || templateId.isBlank() ? "unknown" : templateId; //$NON-NLS-1$
        String value = prompt == null ? "" : prompt; //$NON-NLS-1$
        List<String> sections = requiredSections == null
                ? Collections.emptyList()
                : requiredSections;

        List<String> violations = new ArrayList<>();
        if (value.isBlank()) {
            violations.add("пустой промпт"); //$NON-NLS-1$
        }
        if (!value.isBlank() && value.length() < MIN_PROMPT_LENGTH) {
            violations.add("слишком короткий промпт (" + value.length() + " символов)"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (value.length() > MAX_PROMPT_LENGTH) {
            violations.add("слишком длинный промпт (" + value.length() + " символов)"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!value.contains("## ")) { //$NON-NLS-1$
            violations.add("отсутствуют markdown-секции с заголовками"); //$NON-NLS-1$
        }
        for (String section : sections) {
            if (section != null && !section.isBlank() && !value.contains(section)) {
                violations.add("отсутствует обязательная секция: " + section); //$NON-NLS-1$
            }
        }

        if (!violations.isEmpty()) {
            String details = violations.stream()
                    .map(Objects::toString)
                    .collect(Collectors.joining("; ")); //$NON-NLS-1$
            String message = "Prompt QA failed [" + id + "]: " + details; //$NON-NLS-1$ //$NON-NLS-2$
            if (isStrictMode()) {
                throw new IllegalStateException(message);
            }
            LOG.log(new Status(IStatus.WARNING, PLUGIN_ID, message));
        }

        return value;
    }

    private static boolean isStrictMode() {
        String raw = System.getProperty(PROP_QA_STRICT);
        return raw != null && Boolean.parseBoolean(raw.trim());
    }
}
