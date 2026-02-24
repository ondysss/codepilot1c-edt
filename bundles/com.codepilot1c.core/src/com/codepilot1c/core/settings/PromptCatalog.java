/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.settings;

import java.util.Map;
import java.util.Set;

/**
 * Central catalog for built-in prompt templates and their required placeholders.
 */
public final class PromptCatalog {

    private static final Map<String, String> DEFAULT_TEMPLATES = Map.ofEntries(
            Map.entry(VibePreferenceConstants.PREF_PROMPT_TEMPLATE_EXPLAIN_CODE, """
                    Проанализируй код с архитектурной точки зрения.

                    {{prompt}}

                    Требуется:
                    1) Выделить ответственность кода и его место в слое (domain/app/infra/ui).
                    2) Описать поток данных и точки отказа.
                    3) Найти архитектурные smells: сильная связность, дублирование, скрытые побочные эффекты.
                    4) Дать 2 варианта улучшения: минимальный и целевой.

                    Формат:
                    - Что делает и где в архитектуре
                    - Риски
                    - Вариант A (минимальный)
                    - Вариант B (целевой)
                    """), //$NON-NLS-1$
            Map.entry(VibePreferenceConstants.PREF_PROMPT_TEMPLATE_GENERATE_CODE, """
                    Сгенерируй production-код 1С по задаче с архитектурной дисциплиной.

                    Задача:
                    {{description}}

                    Перед кодом кратко укажи:
                    - Допущения
                    - Границы ответственности
                    - Точки интеграции

                    Потом выдай код и план проверки.
                    """), //$NON-NLS-1$
            Map.entry(VibePreferenceConstants.PREF_PROMPT_TEMPLATE_FIX_CODE, """
                    Исправь код без изменения бизнес-поведения, но с улучшением надежности архитектуры.

                    ```bsl
                    {{code}}
                    ```

                    Обязательно:
                    1) Классифицируй проблемы по severity.
                    2) Покажи минимальный patch.
                    3) Отдельно укажи, что можно вынести в архитектурный рефакторинг позже.
                    """), //$NON-NLS-1$
            Map.entry(VibePreferenceConstants.PREF_PROMPT_TEMPLATE_CRITICISE_CODE, """
                    Сделай архитектурный code review.

                    ```bsl
                    {{code}}
                    ```

                    Оцени:
                    - Границы ответственности
                    - Управление состоянием и транзакциями
                    - Надежность под конкуренцией
                    - Тестопригодность и расширяемость

                    Формат:
                    - Критичные архитектурные дефекты
                    - Важные улучшения
                    - Быстрые победы
                    """), //$NON-NLS-1$
            Map.entry(VibePreferenceConstants.PREF_PROMPT_TEMPLATE_ADD_CODE, """
                    Сгенерируй только вставляемый фрагмент, который не нарушит текущую архитектуру.

                    Контекст:
                    {{context}}

                    Запрос:
                    {{request}}

                    Правила:
                    - Соблюдай текущие границы модуля.
                    - Не добавляй лишние зависимости.
                    - Минимизируй побочные эффекты.

                    Верни только код.
                    """), //$NON-NLS-1$
            Map.entry(VibePreferenceConstants.PREF_PROMPT_TEMPLATE_DOC_COMMENTS, """
                    Сгенерируй комментарий к коду с архитектурным акцентом: ответственность, контракт, ограничения.

                    ```bsl
                    {{code}}
                    ```

                    Верни только блок комментария.
                    """), //$NON-NLS-1$
            Map.entry(VibePreferenceConstants.PREF_PROMPT_TEMPLATE_OPTIMIZE_QUERY, """
                    Оптимизируй SDBL-запрос как архитект производительности.

                    ```sdbl
                    {{query}}
                    ```

                    Дай:
                    1) Архитектурные проблемы запроса (данные/индексы/кардинальность).
                    2) Оптимизированный вариант.
                    3) Риски изменения семантики.
                    4) План проверки эквивалентности и профилирования.
                    """) //$NON-NLS-1$
    );

    private static final Map<String, Set<String>> REQUIRED_PLACEHOLDERS = Map.ofEntries(
            Map.entry(VibePreferenceConstants.PREF_PROMPT_TEMPLATE_EXPLAIN_CODE, Set.of("prompt")), //$NON-NLS-1$
            Map.entry(VibePreferenceConstants.PREF_PROMPT_TEMPLATE_GENERATE_CODE, Set.of("description")), //$NON-NLS-1$
            Map.entry(VibePreferenceConstants.PREF_PROMPT_TEMPLATE_FIX_CODE, Set.of("code")), //$NON-NLS-1$
            Map.entry(VibePreferenceConstants.PREF_PROMPT_TEMPLATE_CRITICISE_CODE, Set.of("code")), //$NON-NLS-1$
            Map.entry(VibePreferenceConstants.PREF_PROMPT_TEMPLATE_ADD_CODE, Set.of("context", "request")), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry(VibePreferenceConstants.PREF_PROMPT_TEMPLATE_DOC_COMMENTS, Set.of("code")), //$NON-NLS-1$
            Map.entry(VibePreferenceConstants.PREF_PROMPT_TEMPLATE_OPTIMIZE_QUERY, Set.of("query")) //$NON-NLS-1$
    );

    private PromptCatalog() {
    }

    /**
     * Returns built-in default template for preference key.
     *
     * @param preferenceKey template preference key
     * @return default template or empty string if key is unknown
     */
    public static String getDefaultTemplate(String preferenceKey) {
        if (preferenceKey == null) {
            return ""; //$NON-NLS-1$
        }
        return DEFAULT_TEMPLATES.getOrDefault(preferenceKey, ""); //$NON-NLS-1$
    }

    /**
     * Returns required placeholders for preference key.
     *
     * @param preferenceKey template preference key
     * @return immutable set of required placeholders
     */
    public static Set<String> getRequiredPlaceholders(String preferenceKey) {
        if (preferenceKey == null) {
            return Set.of();
        }
        return REQUIRED_PLACEHOLDERS.getOrDefault(preferenceKey, Set.of());
    }
}
