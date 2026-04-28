/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import com.codepilot1c.core.model.ToolDefinition;
import com.codepilot1c.core.provider.ProviderCapabilities;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Generates tool call examples for Qwen system prompt priming.
 *
 * <p>Qwen Code (official CLI by Alibaba) always includes XML tool call examples
 * in the system prompt for qwen-coder models. The model was trained on this format
 * and it significantly improves tool call accuracy.</p>
 *
 * <p>Three format styles are supported, selected by model family:</p>
 * <ul>
 *   <li>{@code qwen-coder}: XML {@code <tool_call><function=NAME><parameter=KEY>value</parameter></function></tool_call>}</li>
 *   <li>{@code qwen-vl}: JSON-in-XML {@code <tool_call>{"name": "...", "arguments": {...}}</tool_call>}</li>
 *   <li>general: plain text {@code [tool_call: NAME for ...]}</li>
 * </ul>
 *
 * <p>Examples use actual tool names from the request for realistic priming.
 * This is a priming-only mechanism; actual tool calls go through the structured API.</p>
 */
final class QwenToolCallExamples {

    private QwenToolCallExamples() {
    }

    /**
     * Generates tool call examples appropriate for the model family.
     *
     * @param caps  provider capabilities with resolved model family
     * @param tools the tool definitions available in the current request
     * @return formatted examples string to append to the system prompt
     */
    static String getExamples(ProviderCapabilities caps, List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return ""; //$NON-NLS-1$
        }

        String family = caps.getResolvedModelFamily();
        switch (family) {
            case ProviderCapabilities.FAMILY_QWEN_CODER:
                return buildQwenCoderExamples(tools);
            case ProviderCapabilities.FAMILY_QWEN_VL:
                return buildQwenVlExamples(tools);
            case ProviderCapabilities.FAMILY_QWEN_GENERAL:
                return buildQwenCoderExamples(tools); // qwen-general uses same format
            default:
                return buildGeneralExamples(tools);
        }
    }

    /**
     * Qwen-coder XML format (matches Qwen Code prompts.ts qwenCoderToolCallExamples).
     *
     * <pre>
     * # Tool Call Examples
     *
     * When you need to use a tool, format your tool call like this:
     *
     * &lt;tool_call&gt;
     * &lt;function=read_file&gt;
     * &lt;parameter=file_path&gt;/src/main/java/Example.java&lt;/parameter&gt;
     * &lt;/function&gt;
     * &lt;/tool_call&gt;
     * </pre>
     */
    private static String buildQwenCoderExamples(List<ToolDefinition> tools) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Tool Call Examples\n\n"); //$NON-NLS-1$
        sb.append("When you need to use a tool, format your tool call like the examples below.\n"); //$NON-NLS-1$
        sb.append("You can make multiple tool calls in sequence.\n\n"); //$NON-NLS-1$

        // Generate 1-2 realistic examples from actual tools
        int exampleCount = 0;
        for (ToolDefinition tool : tools) {
            if (exampleCount >= 2) {
                break;
            }
            String example = generateQwenCoderExample(tool);
            if (example != null) {
                sb.append(example);
                sb.append('\n');
                exampleCount++;
            }
        }

        // Add a multi-tool example if we have enough tools
        if (tools.size() >= 2 && exampleCount > 0) {
            sb.append("For multiple tool calls, output them sequentially:\n\n"); //$NON-NLS-1$
            for (int i = 0; i < Math.min(2, tools.size()); i++) {
                String example = generateQwenCoderExample(tools.get(i));
                if (example != null) {
                    sb.append(example);
                }
            }
            sb.append('\n');
        }

        return sb.toString();
    }

    private static String generateQwenCoderExample(ToolDefinition tool) {
        String name = tool.getName();
        ExampleParams params = inferExampleParams(tool);
        if (params == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<tool_call>\n"); //$NON-NLS-1$
        sb.append("<function=").append(name).append(">\n"); //$NON-NLS-1$ //$NON-NLS-2$
        for (int i = 0; i < params.keys.length; i++) {
            sb.append("<parameter=").append(params.keys[i]).append(">"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append(params.values[i]);
            sb.append("</parameter>\n"); //$NON-NLS-1$
        }
        sb.append("</function>\n"); //$NON-NLS-1$
        sb.append("</tool_call>\n"); //$NON-NLS-1$
        return sb.toString();
    }

    /**
     * Qwen-VL JSON-in-XML format.
     *
     * <pre>
     * &lt;tool_call&gt;
     * {"name": "read_file", "arguments": {"file_path": "/src/main/java/Example.java"}}
     * &lt;/tool_call&gt;
     * </pre>
     */
    private static String buildQwenVlExamples(List<ToolDefinition> tools) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Tool Call Examples\n\n"); //$NON-NLS-1$
        sb.append("When you need to use a tool, format your tool call like this:\n\n"); //$NON-NLS-1$

        int exampleCount = 0;
        for (ToolDefinition tool : tools) {
            if (exampleCount >= 2) {
                break;
            }
            ExampleParams params = inferExampleParams(tool);
            if (params == null) {
                continue;
            }

            sb.append("<tool_call>\n"); //$NON-NLS-1$
            sb.append("{\"name\": \"").append(tool.getName()).append("\", \"arguments\": {"); //$NON-NLS-1$ //$NON-NLS-2$
            for (int i = 0; i < params.keys.length; i++) {
                if (i > 0) {
                    sb.append(", "); //$NON-NLS-1$
                }
                sb.append("\"").append(params.keys[i]).append("\": "); //$NON-NLS-1$ //$NON-NLS-2$
                appendJsonValue(sb, params.values[i]);
            }
            sb.append("}}\n"); //$NON-NLS-1$
            sb.append("</tool_call>\n\n"); //$NON-NLS-1$
            exampleCount++;
        }

        return sb.toString();
    }

    /**
     * General plain text format (fallback for non-Qwen models).
     */
    private static String buildGeneralExamples(List<ToolDefinition> tools) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Tool Usage\n\n"); //$NON-NLS-1$
        sb.append("You have access to the following tools. Use the structured tool calling API to invoke them.\n\n"); //$NON-NLS-1$

        int count = 0;
        for (ToolDefinition tool : tools) {
            if (count >= 3) {
                break;
            }
            sb.append("- **").append(tool.getName()).append("**: ").append(tool.getDescription()).append('\n'); //$NON-NLS-1$ //$NON-NLS-2$
            count++;
        }
        if (tools.size() > 3) {
            sb.append("- ... and ").append(tools.size() - 3).append(" more tools\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append('\n');
        return sb.toString();
    }

    /**
     * Infers example parameter key/value pairs from a tool definition.
     * Returns reasonable placeholder values based on common tool patterns.
     */
    private static ExampleParams inferExampleParams(ToolDefinition tool) {
        String name = tool.getName();
        if (name == null) {
            return null;
        }

        ExampleParams curated = inferCuratedExampleParams(name);
        if (curated != null) {
            return curated;
        }

        return inferSchemaDrivenExampleParams(tool);
    }

    private static ExampleParams inferCuratedExampleParams(String name) {
        if ("git_inspect".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"operation", "project_name"}, //$NON-NLS-1$ //$NON-NLS-2$
                    new String[]{"status", "DemoConfiguration"}); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if ("git_mutate".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"operation", "project_name", "remote_name", "remote_url"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    new String[]{"remote_add", "DemoConfiguration", "origin", "https://example.com/repo.git"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        if ("bsl_symbol_at_position".equals(name) || "bsl_type_at_position".equals(name)) { //$NON-NLS-1$ //$NON-NLS-2$
            return new ExampleParams(
                    new String[]{"projectName", "filePath", "line", "column"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    new String[]{"DemoConfiguration", "CommonModules/Orders/Module.bsl", "12", "8"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        if ("bsl_scope_members".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"projectName", "filePath", "line", "column", "contains"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                    new String[]{"DemoConfiguration", "CommonModules/Orders/Module.bsl", "12", "8", "Заказ"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        }
        if ("bsl_list_methods".equals(name) || "bsl_module_exports".equals(name)) { //$NON-NLS-1$ //$NON-NLS-2$
            return new ExampleParams(
                    new String[]{"projectName", "filePath", "name_contains"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    new String[]{"DemoConfiguration", "CommonModules/Orders/Module.bsl", "Провести"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if ("bsl_get_method_body".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"projectName", "filePath", "name"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    new String[]{"DemoConfiguration", "CommonModules/Orders/Module.bsl", "ПровестиЗаказ"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if ("bsl_analyze_method".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"projectName", "filePath", "methodName"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    new String[]{"DemoConfiguration", "CommonModules/Orders/Module.bsl", "ПровестиЗаказ"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if ("bsl_module_context".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"projectName", "filePath"}, //$NON-NLS-1$ //$NON-NLS-2$
                    new String[]{"DemoConfiguration", "CommonModules/Orders/Module.bsl"}); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if ("edt_get_configuration_properties".equals(name) //$NON-NLS-1$
                || "edt_get_problem_summary".equals(name) //$NON-NLS-1$
                || "edt_get_tags".equals(name) //$NON-NLS-1$
                || "edt_list_modules".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"projectName"}, //$NON-NLS-1$
                    new String[]{"DemoConfiguration"}); //$NON-NLS-1$
        }
        if ("edt_get_objects_by_tags".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"projectName", "tags"}, //$NON-NLS-1$ //$NON-NLS-2$
                    new String[]{"DemoConfiguration", "sales"}); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if ("edt_get_module_structure".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"projectName", "moduleFqn", "full"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    new String[]{"DemoConfiguration", "CommonModule.Orders.Module", "true"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if ("edt_search_in_code".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"projectName", "query", "searchType", "scope"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    new String[]{"DemoConfiguration", "ПровестиЗаказ", "text", "modules"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        if ("edt_get_method_call_hierarchy".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"projectName", "methodFqn", "direction", "depth"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    new String[]{"DemoConfiguration", "CommonModule.Orders.Module.ПровестиЗаказ", "both", "1"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        if ("edt_go_to_definition".equals(name) || "edt_get_symbol_info".equals(name)) { //$NON-NLS-1$ //$NON-NLS-2$
            return new ExampleParams(
                    new String[]{"projectName", "symbolFqn"}, //$NON-NLS-1$ //$NON-NLS-2$
                    new String[]{"DemoConfiguration", "CommonModule.Orders.Module.ПровестиЗаказ"}); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if ("skill".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"name"}, //$NON-NLS-1$
                    new String[]{"review"}); //$NON-NLS-1$
        }
        if ("delegate_to_agent".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"agentType", "task", "context"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    new String[]{"metadata", "Create catalog Items and list form", "Project=DemoConfiguration; object=Catalog.Items"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if ("task".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"description", "prompt", "profile"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    new String[]{"metadata task", "Create catalog Items and add list form", "metadata"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        // Composite tools
        if ("dcs_manage".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"command", "project", "owner_fqn"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    new String[]{"get_summary", "DemoConfiguration", "Report.SalesReport"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if ("extension_manage".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"command", "project", "base_project", "extension_project", "source_object_fqn", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                            "validation_token"}, //$NON-NLS-1$
                    new String[]{"adopt", "DemoConfiguration", "DemoConfiguration", "ExtensionDemo", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                            "Catalog.Items", "validation-token-123"}); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if ("extension_adopt_object".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"project", "base_project", "extension_project", "source_object_fqn", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                            "validation_token"}, //$NON-NLS-1$
                    new String[]{"DemoConfiguration", "DemoConfiguration", "ExtensionDemo", "Catalog.Items", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                            "validation-token-123"}); //$NON-NLS-1$
        }
        if ("external_manage".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"command", "project"}, //$NON-NLS-1$ //$NON-NLS-2$
                    new String[]{"list_objects", "ExternalProcessing"}); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if ("edt_diagnostics".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"command"}, //$NON-NLS-1$
                    new String[]{"metadata_smoke"}); //$NON-NLS-1$
        }
        if ("get_diagnostics".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"scope", "path"}, //$NON-NLS-1$ //$NON-NLS-2$
                    new String[]{"file", "/DemoConfiguration/src/CommonModules/Orders/Module.bsl"}); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if ("get_bookmarks".equals(name) || "get_tasks".equals(name)) { //$NON-NLS-1$ //$NON-NLS-2$
            return new ExampleParams(
                    new String[]{"projectName", "limit"}, //$NON-NLS-1$ //$NON-NLS-2$
                    new String[]{"DemoConfiguration", "100"}); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if ("qa_inspect".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"command"}, //$NON-NLS-1$
                    new String[]{"status"}); //$NON-NLS-1$
        }
        if ("qa_generate".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"command"}, //$NON-NLS-1$
                    new String[]{"init_config"}); //$NON-NLS-1$
        }
        if ("run_yaxunit_tests".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"project_name", "filters", "timeout_s"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    new String[]{"DemoConfiguration", "CatalogItems", "300"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if ("debug_yaxunit_tests".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"project_name", "filters", "wait_for_debugger"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    new String[]{"DemoConfiguration", "CatalogItems", "true"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if ("set_breakpoint".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"projectName", "filePath", "line"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    new String[]{"DemoConfiguration", "CommonModules/Orders/Module.bsl", "12"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if ("remove_breakpoint".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"projectName", "breakpointId"}, //$NON-NLS-1$ //$NON-NLS-2$
                    new String[]{"DemoConfiguration", "12345"}); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if ("list_breakpoints".equals(name) || "debug_status".equals(name)) { //$NON-NLS-1$ //$NON-NLS-2$
            return new ExampleParams(
                    new String[]{"projectName"}, //$NON-NLS-1$
                    new String[]{"DemoConfiguration"}); //$NON-NLS-1$
        }
        if ("wait_for_break".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"projectName", "timeoutMs"}, //$NON-NLS-1$ //$NON-NLS-2$
                    new String[]{"DemoConfiguration", "30000"}); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if ("get_variables".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"projectName", "threadId"}, //$NON-NLS-1$ //$NON-NLS-2$
                    new String[]{"DemoConfiguration", "Thread@1a2b3c"}); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if ("step".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"projectName", "kind"}, //$NON-NLS-1$ //$NON-NLS-2$
                    new String[]{"DemoConfiguration", "over"}); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if ("resume".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"projectName"}, //$NON-NLS-1$
                    new String[]{"DemoConfiguration"}); //$NON-NLS-1$
        }
        if ("evaluate_expression".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"projectName", "expression"}, //$NON-NLS-1$ //$NON-NLS-2$
                    new String[]{"DemoConfiguration", "ТекущийОбъект"}); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if ("render_template".equals(name)) { //$NON-NLS-1$
            // Use only flat params for XML fallback safety — sections is complex so use minimal keys
            return new ExampleParams(
                    new String[]{"project", "template_fqn", "validation_token"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    new String[]{"DemoConfiguration", "Document.ПоступлениеТоваров.Template.Макет", "abc123"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if ("inspect_template".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"project", "template_fqn"}, //$NON-NLS-1$ //$NON-NLS-2$
                    new String[]{"DemoConfiguration", "Document.ПоступлениеТоваров.Template.Макет"}); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if ("discover_tools".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"category"}, //$NON-NLS-1$
                    new String[]{"metadata"}); //$NON-NLS-1$
        }
        if ("remember_fact".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"content", "category", "domain"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    new String[]{"Регистр ОстаткиТоваров используется для учёта складских остатков", "FACT", "accumulation-registers"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        // Form model mutation — set_item с типом поля (ManagedFormFieldType литерал).
        // Пример специально показывает смену виджета существующего поля, потому что это часто
        // ошибочно интерпретируется как неподдерживаемое действие (набор операций в operations
        // не документирует payload-ключи в JSON schema — агент может решить, что type недоступен).
        if ("mutate_form_model".equals(name)) { //$NON-NLS-1$
            return new ExampleParams(
                    new String[]{"project", "form_fqn", "operations", "validation_token"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    new String[]{"DemoConfiguration", "Catalog.Items.Form.ListForm", //$NON-NLS-1$ //$NON-NLS-2$
                            "[{\"op\":\"set_item\",\"id\":\"MyField\",\"set\":{\"type\":\"LABEL_FIELD\"}}]", //$NON-NLS-1$
                            "validation-token-123"}); //$NON-NLS-1$
        }
        return null;
    }

    private static ExampleParams inferSchemaDrivenExampleParams(ToolDefinition tool) {
        JsonObject schema = parseSchema(tool.getParametersSchema());
        if (schema == null || !schema.has("properties") || !schema.get("properties").isJsonObject()) { //$NON-NLS-1$ //$NON-NLS-2$
            return new ExampleParams(new String[0], new String[0]);
        }

        JsonObject properties = schema.getAsJsonObject("properties"); //$NON-NLS-1$
        List<String> orderedKeys = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        if (schema.has("required") && schema.get("required").isJsonArray()) { //$NON-NLS-1$ //$NON-NLS-2$
            JsonArray required = schema.getAsJsonArray("required"); //$NON-NLS-1$
            for (JsonElement element : required) {
                String key = element.getAsString();
                if (properties.has(key) && seen.add(key)) {
                    orderedKeys.add(key);
                }
            }
        }

        int optionalBudget = orderedKeys.isEmpty() ? 2 : 2;
        for (String key : properties.keySet()) {
            if (seen.contains(key)) {
                continue;
            }
            if (optionalBudget <= 0 && !orderedKeys.isEmpty()) {
                break;
            }
            orderedKeys.add(key);
            seen.add(key);
            optionalBudget--;
        }

        if (orderedKeys.isEmpty()) {
            return new ExampleParams(new String[0], new String[0]);
        }

        List<String> values = new ArrayList<>();
        for (String key : orderedKeys) {
            JsonObject propertySchema = properties.get(key).isJsonObject()
                    ? properties.getAsJsonObject(key)
                    : new JsonObject();
            values.add(inferExampleValue(key, propertySchema));
        }

        return new ExampleParams(orderedKeys.toArray(new String[0]), values.toArray(new String[0]));
    }

    private static JsonObject parseSchema(String schemaText) {
        if (schemaText == null || schemaText.isBlank()) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(schemaText);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String inferExampleValue(String key, JsonObject propertySchema) {
        if (propertySchema.has("enum") && propertySchema.get("enum").isJsonArray()) { //$NON-NLS-1$ //$NON-NLS-2$
            JsonArray values = propertySchema.getAsJsonArray("enum"); //$NON-NLS-1$
            if (!values.isEmpty()) {
                return primitiveAsString(values.get(0));
            }
        }

        String normalizedKey = key.toLowerCase(Locale.ROOT);
        String type = propertySchema.has("type") ? propertySchema.get("type").getAsString() : "string"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        switch (type) {
            case "boolean": //$NON-NLS-1$
                return "true"; //$NON-NLS-1$
            case "integer": //$NON-NLS-1$
                return normalizedKey.contains("column") ? "8" : "12"; //$NON-NLS-1$ //$NON-NLS-2$
            case "number": //$NON-NLS-1$
                return "1"; //$NON-NLS-1$
            case "array": //$NON-NLS-1$
                return inferArrayValue(key, propertySchema);
            case "object": //$NON-NLS-1$
                return inferObjectValue(normalizedKey);
            default:
                return inferStringValue(normalizedKey);
        }
    }

    private static String inferArrayValue(String key, JsonObject propertySchema) {
        JsonObject itemSchema = propertySchema.has("items") && propertySchema.get("items").isJsonObject() //$NON-NLS-1$ //$NON-NLS-2$
                ? propertySchema.getAsJsonObject("items") //$NON-NLS-1$
                : new JsonObject();
        String itemType = itemSchema.has("type") ? itemSchema.get("type").getAsString() : "string"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if ("object".equals(itemType)) { //$NON-NLS-1$
            return "[" + inferObjectValue(key.toLowerCase(Locale.ROOT)) + "]"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if ("integer".equals(itemType) || "number".equals(itemType)) { //$NON-NLS-1$ //$NON-NLS-2$
            return "[1]"; //$NON-NLS-1$
        }
        return "[\"" + escapeJson(inferStringValue(key.toLowerCase(Locale.ROOT))) + "\"]"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String inferObjectValue(String normalizedKey) {
        if (normalizedKey.contains("payload")) { //$NON-NLS-1$
            return "{\"kind\":\"Catalog\",\"name\":\"Items\"}"; //$NON-NLS-1$
        }
        if (normalizedKey.contains("change")) { //$NON-NLS-1$
            return "{\"synonym\":\"Товары\"}"; //$NON-NLS-1$
        }
        if (normalizedKey.contains("operation")) { //$NON-NLS-1$
            return "{\"op\":\"set-title\",\"value\":\"Items\"}"; //$NON-NLS-1$
        }
        return "{\"value\":\"example\"}"; //$NON-NLS-1$
    }

    private static String inferStringValue(String normalizedKey) {
        if (normalizedKey.contains("validation_token")) { //$NON-NLS-1$
            return "validation-token-123"; //$NON-NLS-1$
        }
        if (normalizedKey.contains("projectname") || normalizedKey.contains("project_name") || "project".equals(normalizedKey)) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return "DemoConfiguration"; //$NON-NLS-1$
        }
        if (normalizedKey.contains("base_project")) { //$NON-NLS-1$
            return "BaseConfiguration"; //$NON-NLS-1$
        }
        if (normalizedKey.contains("extension_project")) { //$NON-NLS-1$
            return "ExtensionDemo"; //$NON-NLS-1$
        }
        if (normalizedKey.contains("external_project")) { //$NON-NLS-1$
            return "ExternalProcessing"; //$NON-NLS-1$
        }
        if (normalizedKey.contains("source_project_name")) { //$NON-NLS-1$
            return "SourceConfiguration"; //$NON-NLS-1$
        }
        if (normalizedKey.contains("target_project_name")) { //$NON-NLS-1$
            return "TargetConfiguration"; //$NON-NLS-1$
        }
        if (normalizedKey.contains("repo_path")) { //$NON-NLS-1$
            return "/workspace/demo-repo"; //$NON-NLS-1$
        }
        if (normalizedKey.contains("remote_url")) { //$NON-NLS-1$
            return "https://example.com/repo.git"; //$NON-NLS-1$
        }
        if (normalizedKey.contains("filepath") || normalizedKey.endsWith("path")) { //$NON-NLS-1$ //$NON-NLS-2$
            return "CommonModules/Orders/Module.bsl"; //$NON-NLS-1$
        }
        if (normalizedKey.contains("owner_fqn")) { //$NON-NLS-1$
            return "Catalog.Items"; //$NON-NLS-1$
        }
        if (normalizedKey.contains("target_fqn") || normalizedKey.contains("object_fqn") || normalizedKey.contains("parent_fqn")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return "Catalog.Items"; //$NON-NLS-1$
        }
        if (normalizedKey.contains("source_object_fqn")) { //$NON-NLS-1$
            return "Catalog.Items"; //$NON-NLS-1$
        }
        if (normalizedKey.contains("form_fqn")) { //$NON-NLS-1$
            return "Catalog.Items.Form.ListForm"; //$NON-NLS-1$
        }
        if (normalizedKey.contains("objectfqns")) { //$NON-NLS-1$
            return "[\"Catalog.Items\"]"; //$NON-NLS-1$
        }
        if (normalizedKey.contains("child_kind")) { //$NON-NLS-1$
            return "ATTRIBUTE"; //$NON-NLS-1$
        }
        if (normalizedKey.contains("kind")) { //$NON-NLS-1$
            return "Catalog"; //$NON-NLS-1$
        }
        if ("name".equals(normalizedKey) || normalizedKey.endsWith("_name") || normalizedKey.contains("dataset_name")
                || normalizedKey.contains("parameter_name")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return "Items"; //$NON-NLS-1$
        }
        if (normalizedKey.contains("feature_file")) { //$NON-NLS-1$
            return "features/catalog_items.feature"; //$NON-NLS-1$
        }
        if (normalizedKey.contains("state")) { //$NON-NLS-1$
            return "managed"; //$NON-NLS-1$
        }
        if (normalizedKey.contains("property_name")) { //$NON-NLS-1$
            return "Synonym"; //$NON-NLS-1$
        }
        if (normalizedKey.contains("usage")) { //$NON-NLS-1$
            return "list_form"; //$NON-NLS-1$
        }
        if (normalizedKey.contains("query") || normalizedKey.contains("pattern") || normalizedKey.contains("contains")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return "Заказ"; //$NON-NLS-1$
        }
        if (normalizedKey.contains("goal")) { //$NON-NLS-1$
            return "Validate catalog Items workflow"; //$NON-NLS-1$
        }
        if (normalizedKey.contains("description")) { //$NON-NLS-1$
            return "Create catalog Items and list form"; //$NON-NLS-1$
        }
        if (normalizedKey.contains("prompt") || normalizedKey.contains("task")) { //$NON-NLS-1$ //$NON-NLS-2$
            return "Create catalog Items and list form"; //$NON-NLS-1$
        }
        if (normalizedKey.contains("operation")) { //$NON-NLS-1$
            return "inspect"; //$NON-NLS-1$
        }
        if (normalizedKey.contains("plan")) { //$NON-NLS-1$
            return "Compile metadata and smoke-check form"; //$NON-NLS-1$
        }
        if (normalizedKey.contains("tool_result")) { //$NON-NLS-1$
            return "{\"status\":\"error\",\"message\":\"Validation failed\"}"; //$NON-NLS-1$
        }
        if (normalizedKey.contains("agenttype")) { //$NON-NLS-1$
            return "metadata"; //$NON-NLS-1$
        }
        return "example value"; //$NON-NLS-1$
    }

    private static void appendJsonValue(StringBuilder sb, String value) {
        if (value == null) {
            sb.append("null"); //$NON-NLS-1$
            return;
        }
        String trimmed = value.trim();
        if (looksLikeJsonLiteral(trimmed)) {
            sb.append(trimmed);
            return;
        }
        sb.append("\"").append(escapeJson(value)).append("\""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean looksLikeJsonLiteral(String value) {
        return value.startsWith("{") || value.startsWith("[") //$NON-NLS-1$ //$NON-NLS-2$
                || "true".equals(value) || "false".equals(value) || "null".equals(value) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || value.matches("-?\\d+(\\.\\d+)?"); //$NON-NLS-1$
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    private static String primitiveAsString(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "null"; //$NON-NLS-1$
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return element.getAsString();
        }
        return element.toString();
    }

    private static final class ExampleParams {
        final String[] keys;
        final String[] values;

        ExampleParams(String[] keys, String[] values) {
            this.keys = keys;
            this.values = values;
        }
    }
}
