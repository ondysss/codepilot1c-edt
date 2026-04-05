/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.surface;

import com.codepilot1c.core.model.ToolDefinition;

/**
 * Rewrites the model-facing backend tool surface with Qwen-specific descriptions.
 */
public final class QwenToolSurfaceRewriteContributor implements ToolSurfaceContributor {

    @Override
    public boolean supports(ToolSurfaceContext context) {
        return context != null && context.isBuiltIn() && context.isBackendSelectedInUi();
    }

    @Override
    public void contribute(ToolSurfaceContext context, ToolDefinition.Builder builder) {
        String name = builder.getName();
        String description = overrideDescription(name);
        if (description != null) {
            builder.description(description);
        }
        builder.parametersSchema(ToolSurfaceSchemaNormalizer.normalizeBuiltIn(name, builder.getParametersSchema()));
    }

    @Override
    public int getOrder() {
        return -200;
    }

    private String overrideDescription(String toolName) {
        return switch (toolName) {
            case "read_file" -> "Read an existing workspace file or a 1-based line range. Use it for exact source inspection after discovery and keep paths workspace-relative."; //$NON-NLS-1$
            case "list_files" -> "List projects or directory contents in the workspace. Use it for pathname discovery, not for text search or semantic symbol lookup."; //$NON-NLS-1$
            case "glob" -> "Find files by glob pattern under the workspace or a subdirectory. Use it for pathname discovery before read_file."; //$NON-NLS-1$
            case "grep" -> "Search plain text or regex across workspace files. Use it for string occurrences only; prefer EDT semantic tools for symbols, metadata, and platform behavior."; //$NON-NLS-1$
            case "edit_file" -> "Edit an existing workspace file in place via full replace, targeted search/replace, or SEARCH/REPLACE blocks. Do not use it to create files or mutate EDT metadata descriptors unless an explicit emergency override is intended."; //$NON-NLS-1$
            case "write_file" -> "Overwrite an existing workspace file with full content. Prefer edit_file for narrow patches and ensure_module_artifact before touching metadata-owned BSL modules."; //$NON-NLS-1$
            case "workspace_import_project" -> "Import an existing Eclipse/EDT project directory into the current workspace. Inspect repository and project state first, then import only when a .project-based project already exists."; //$NON-NLS-1$
            case "git_inspect" -> "Показывает состояние git-репозитория через безопасные read-only операции. Для EDT проекта предпочитай project_name; repo_path используй только как явный override."; //$NON-NLS-1$
            case "git_mutate" -> "Выполняет разрешённые git-изменения. Для существующего EDT проекта передавай project_name, а для init/create/clone обязательно указывай repo_path."; //$NON-NLS-1$
            case "git_clone_and_import_project" -> "Clone a git repository and import an existing Eclipse/EDT project from it into the workspace. Prefer it only when both clone and workspace import are required in one step."; //$NON-NLS-1$
            case "import_project_from_infobase" -> "Create a new EDT project by exporting configuration from the infobase associated with an existing EDT project. Use dry_run first when runtime or infobase availability is uncertain."; //$NON-NLS-1$
            case "edt_content_assist" -> "Return EDT AST-aware content assist for a BSL position. Prefer it over grep when you need semantic completions or symbol-aware editing help."; //$NON-NLS-1$
            case "edt_find_references" -> "Find semantic references for metadata objects or EDT-resolved symbols. Prefer it over raw text search for usage questions."; //$NON-NLS-1$
            case "edt_metadata_details" -> "Read structured EDT metadata details for one or more object FQNs. Use it for configuration structure, not for platform-language reference."; //$NON-NLS-1$
            case "scan_metadata_index" -> "List top-level metadata objects in an EDT configuration with scope and name filters. Use it before deeper metadata inspection or mutation."; //$NON-NLS-1$
            case "edt_field_type_candidates" -> "List valid EDT type candidates for a metadata field such as type. Use it to resolve diagnostics about missing or invalid types."; //$NON-NLS-1$
            case "inspect_platform_reference" -> "Read EDT platform-language documentation for builtin types, methods, and properties. Use it for platform API questions, not configuration metadata."; //$NON-NLS-1$
            case "bsl_symbol_at_position" -> "Resolve the semantic BSL symbol at a source position, including its kind and owning container."; //$NON-NLS-1$
            case "bsl_type_at_position" -> "Resolve the inferred BSL type at a source position. Use it instead of guessing expression types from text."; //$NON-NLS-1$
            case "bsl_scope_members" -> "List members available in scope at a BSL position. Use it when you need semantic completion candidates or visible API surface."; //$NON-NLS-1$
            case "bsl_list_methods" -> "List methods for a platform type or EDT-resolved semantic type. Prefer it over manual platform-doc scanning when you already know the target type."; //$NON-NLS-1$
            case "bsl_get_method_body" -> "Read the body of a resolved BSL method or procedure declaration when the EDT semantic layer can locate it."; //$NON-NLS-1$
            case "bsl_analyze_method" -> "Analyze one BSL method for complexity, call graph, unused parameters, and risky flow patterns."; //$NON-NLS-1$
            case "bsl_module_context" -> "Read module-level BSL context: module type, owner, default pragmas, and method counts."; //$NON-NLS-1$
            case "bsl_module_exports" -> "List exported procedures and functions of one BSL module with signatures and line ranges."; //$NON-NLS-1$
            case "edt_validate_request" -> "Проверяет запрос на изменение метаданных и выдаёт ОДНОРАЗОВЫЙ validation_token. Каждый токен может быть использован ТОЛЬКО ОДИН РАЗ — для каждой новой мутации запрашивай НОВЫЙ токен. Обязателен перед metadata/forms/DCS/extension/external мутациями."; //$NON-NLS-1$
            case "create_form" -> "Создаёт управляемую форму через BM API. owner_fqn: ПОЛНЫЙ FQN с типом (Document.ПоступлениеТоваров, ExternalDataProcessor.МояОбработка). НЕ используй короткое имя. После создания запусти диагностику."; //$NON-NLS-1$
            case "create_metadata" -> "Создаёт метаданный объект через BM API. Свойства: COMMON_MODULE — clientManagedApplication/server/global. DOCUMENT — useStandardCommands. CATALOG — hierarchical+hierarchyType. После создания запусти диагностику."; //$NON-NLS-1$
            case "add_metadata_child" -> "Создаёт дочерний объект (реквизит, табличную часть, команду). Тип: {types:[xs:string]} или {types:[CatalogRef.Склады]}. НЕ используй v8:STRING."; //$NON-NLS-1$
            case "ensure_module_artifact" -> "Ensure that a metadata-owned BSL module artifact exists and return its path. Use it after edt_validate_request and before edit_file or write_file when changing object-owned module code."; //$NON-NLS-1$
            case "update_metadata" -> "Применяет изменения свойств через BM API. Формат changes: {\"set\":{\"field\":\"value\"}} — обёртка set ОБЯЗАТЕЛЬНА. Enum-поля: строки (Allow/Deny). НЕ используй: subsystems, name. После изменений запусти диагностику."; //$NON-NLS-1$
            case "mutate_form_model" -> "Применяет низкоуровневые операции к форме. Каждая операция содержит поле op (НЕ id!): add_field, add_group, move, remove, set_property. Поле name ОБЯЗАТЕЛЬНО для add_field/add_group. Сначала inspect_form_layout."; //$NON-NLS-1$
            case "delete_metadata" -> "Delete a metadata object through EDT BM APIs with an explicit validation_token. Use recursive or force only when the request truly requires it."; //$NON-NLS-1$
            case "apply_form_recipe" -> "Apply a higher-level managed-form recipe that can create or locate a form, upsert attributes, and mutate layout. Prefer it over low-level mutate_form_model when the intended change fits the recipe flow."; //$NON-NLS-1$
            case "inspect_form_layout" -> "Inspect managed-form structure headlessly through EDT BM APIs. Use it before form mutations to locate element ids, data paths, and layout nodes."; //$NON-NLS-1$
            // QA tools (still registered individually)
            case "qa_prepare_form_context" -> "Prepare structured QA context for a managed form, including any required pre-validation or form creation steps."; //$NON-NLS-1$
            case "qa_plan_scenario" -> "Generate a QA scenario plan from the prepared context and requested intent. Use it before compiling or running a feature."; //$NON-NLS-1$
            case "qa_validate_feature" -> "Validate generated QA feature assets and surface actionable defects before execution."; //$NON-NLS-1$
            case "qa_run" -> "Run the prepared QA scenario and return machine-readable execution results. Use it only after config, context, and feature validation are complete."; //$NON-NLS-1$
            case "author_yaxunit_tests" -> "Generate or update YAxUnit tests for a metadata object or module in a validated QA-oriented authoring flow."; //$NON-NLS-1$
            // Smoke tools (still registered individually, gated by ToolContextGate)
            case "edt_extension_smoke" -> "Run smoke verification focused on EDT extension workflows and report the exact failing stage."; //$NON-NLS-1$
            case "edt_external_smoke" -> "Run smoke verification focused on EDT external-object workflows and report the exact failing stage."; //$NON-NLS-1$
            // Composite tools
            case "dcs_manage" -> "Управляет СКД: читает состояние, создаёт основную схему, обновляет наборы данных, параметры и поля. ВАЖНО: owner-объект ДОЛЖЕН существовать — сначала создай через create_metadata."; //$NON-NLS-1$
            case "extension_manage" -> "Управляет расширениями EDT: показывает проекты и объекты, создаёт расширение, заимствует объект из базы и меняет состояние свойства."; //$NON-NLS-1$
            case "external_manage" -> "Управляет внешними обработками/отчётами. object_fqn: ПОЛНЫЙ FQN (ExternalDataProcessor.МояОбработка, ExternalReport.МойОтчёт). НЕ используй короткое имя без типа."; //$NON-NLS-1$
            case "edt_diagnostics" -> "Запускает EDT диагностику и runtime-команды: smoke, trace export, разбор ошибок, обновление инфобазы и запуск приложения."; //$NON-NLS-1$
            case "qa_inspect" -> "Читает состояние QA без изменений файлов: объясняет qa-config, проверяет окружение и ищет доступные шаги Vanessa Automation."; //$NON-NLS-1$
            case "qa_generate" -> "Генерирует QA-артефакты: создаёт или мигрирует qa-config и собирает feature-файл из структурированного сценарного плана."; //$NON-NLS-1$
            case "discover_tools" -> "Показывает скрытые domain tools по категории, когда текущей поверхности недостаточно. Сам работу не выполняет, только раскрывает инструменты."; //$NON-NLS-1$
            default -> null;
        };
    }
}
