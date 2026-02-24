/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.internal;

/**
 * Centralized human-readable tool names used across UI widgets and dialogs.
 */
public final class ToolDisplayNames {

    private ToolDisplayNames() {
        // Utility class.
    }

    public static String get(String name) {
        if (name == null) {
            return ""; //$NON-NLS-1$
        }
        return switch (name) {
            case "read_file" -> "Чтение файла"; //$NON-NLS-1$ //$NON-NLS-2$
            case "edit_file" -> "Редактирование файла"; //$NON-NLS-1$ //$NON-NLS-2$
            case "write_file" -> "Создание файла"; //$NON-NLS-1$ //$NON-NLS-2$
            case "list_files" -> "Список файлов"; //$NON-NLS-1$ //$NON-NLS-2$
            case "glob" -> "Поиск файлов"; //$NON-NLS-1$ //$NON-NLS-2$
            case "grep" -> "Поиск текста"; //$NON-NLS-1$ //$NON-NLS-2$
            case "shell" -> "Команда оболочки"; //$NON-NLS-1$ //$NON-NLS-2$
            case "edt_content_assist" -> "EDT автодополнение"; //$NON-NLS-1$ //$NON-NLS-2$
            case "edt_find_references" -> "EDT поиск ссылок"; //$NON-NLS-1$ //$NON-NLS-2$
            case "edt_metadata_details" -> "EDT детали метаданных"; //$NON-NLS-1$ //$NON-NLS-2$
            case "scan_metadata_index" -> "Индекс метаданных EDT"; //$NON-NLS-1$ //$NON-NLS-2$
            case "dcs_get_summary" -> "СКД сводка"; //$NON-NLS-1$ //$NON-NLS-2$
            case "dcs_list_nodes" -> "СКД узлы"; //$NON-NLS-1$ //$NON-NLS-2$
            case "dcs_create_main_schema" -> "СКД создать основную схему"; //$NON-NLS-1$ //$NON-NLS-2$
            case "dcs_upsert_query_dataset" -> "СКД набор данных запроса"; //$NON-NLS-1$ //$NON-NLS-2$
            case "dcs_upsert_parameter" -> "СКД параметр"; //$NON-NLS-1$ //$NON-NLS-2$
            case "dcs_upsert_calculated_field" -> "СКД вычисляемое поле"; //$NON-NLS-1$ //$NON-NLS-2$
            case "extension_list_projects" -> "Расширения конфигурации"; //$NON-NLS-1$ //$NON-NLS-2$
            case "extension_list_objects" -> "Объекты расширения"; //$NON-NLS-1$ //$NON-NLS-2$
            case "external_list_projects" -> "Проекты внешних объектов"; //$NON-NLS-1$ //$NON-NLS-2$
            case "external_list_objects" -> "Внешние отчеты и обработки"; //$NON-NLS-1$ //$NON-NLS-2$
            case "external_get_details" -> "Детали внешнего объекта"; //$NON-NLS-1$ //$NON-NLS-2$
            case "external_create_report" -> "Создание внешнего отчета"; //$NON-NLS-1$ //$NON-NLS-2$
            case "external_create_processing" -> "Создание внешней обработки"; //$NON-NLS-1$ //$NON-NLS-2$
            case "extension_create_project" -> "Создание расширения"; //$NON-NLS-1$ //$NON-NLS-2$
            case "extension_adopt_object" -> "Добавление объекта в расширение"; //$NON-NLS-1$ //$NON-NLS-2$
            case "extension_set_property_state" -> "Состояние свойства расширения"; //$NON-NLS-1$ //$NON-NLS-2$
            case "inspect_form_layout" -> "Структура формы"; //$NON-NLS-1$ //$NON-NLS-2$
            case "get_platform_documentation", "inspect_platform_reference" -> "Справка платформы"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "bsl_symbol_at_position" -> "BSL символ по позиции"; //$NON-NLS-1$ //$NON-NLS-2$
            case "bsl_type_at_position" -> "BSL тип по позиции"; //$NON-NLS-1$ //$NON-NLS-2$
            case "bsl_scope_members" -> "BSL элементы области"; //$NON-NLS-1$ //$NON-NLS-2$
            case "edt_validate_request" -> "Валидация запроса EDT"; //$NON-NLS-1$ //$NON-NLS-2$
            case "create_metadata" -> "Создание метаданных EDT"; //$NON-NLS-1$ //$NON-NLS-2$
            case "create_form" -> "Создание формы EDT"; //$NON-NLS-1$ //$NON-NLS-2$
            case "add_metadata_child" -> "Создание вложенных метаданных EDT"; //$NON-NLS-1$ //$NON-NLS-2$
            case "update_metadata" -> "Обновление метаданных EDT"; //$NON-NLS-1$ //$NON-NLS-2$
            case "mutate_form_model" -> "Изменение модели формы EDT"; //$NON-NLS-1$ //$NON-NLS-2$
            case "delete_metadata" -> "Удаление метаданных EDT"; //$NON-NLS-1$ //$NON-NLS-2$
            case "edt_trace_export" -> "Трейс экспорта EDT"; //$NON-NLS-1$ //$NON-NLS-2$
            case "edt_metadata_smoke" -> "Smoke метаданных EDT"; //$NON-NLS-1$ //$NON-NLS-2$
            case "edt_extension_smoke" -> "Smoke расширений EDT"; //$NON-NLS-1$ //$NON-NLS-2$
            case "edt_external_smoke" -> "Smoke внешних объектов EDT"; //$NON-NLS-1$ //$NON-NLS-2$
            case "get_diagnostics" -> "Диагностики"; //$NON-NLS-1$ //$NON-NLS-2$
            case "list_metadata" -> "Список метаданных"; //$NON-NLS-1$ //$NON-NLS-2$
            case "get_metadata" -> "Получение метаданных"; //$NON-NLS-1$ //$NON-NLS-2$
            case "open_module" -> "Открытие модуля"; //$NON-NLS-1$ //$NON-NLS-2$
            case "task" -> "Подзадача"; //$NON-NLS-1$ //$NON-NLS-2$
            default -> name;
        };
    }
}
