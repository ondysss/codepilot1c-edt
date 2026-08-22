/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.profiles;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.codepilot1c.core.agent.prompts.AgentPromptTemplates;
import com.codepilot1c.core.agent.prompts.PromptProviderRegistry;
import com.codepilot1c.core.permissions.PermissionRule;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolRegistry;

/**
 * Базовый профиль для strict GSD Native фаз.
 *
 * <p>Каждая фаза наследует read-only набор инструментов для исследования
 * проекта и добавляет строго ограниченный набор фазовых GSD-инструментов.
 * Project/EDT/Git мутации запрещены для discuss/plan/verify.</p>
 */
abstract class GsdPhaseProfile implements AgentProfile {

    protected static final List<PermissionRule> BASE_READ_PERMISSIONS;

    static {
        List<PermissionRule> rules = Arrays.asList(
                PermissionRule.allow("read_file").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("glob").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("grep").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("list_files").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("git_inspect").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("get_diagnostics").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("get_bookmarks").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("get_tasks").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("edt_content_assist").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("edt_find_references").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("edt_metadata_details").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("scan_metadata_index").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("edt_get_configuration_properties").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("edt_get_problem_summary").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("edt_get_tags").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("edt_get_objects_by_tags").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("edt_list_modules").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("edt_get_module_structure").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("edt_search_in_code").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("edt_get_method_call_hierarchy").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("edt_get_project_call_graph").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("edt_go_to_definition").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("edt_get_symbol_info").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("inspect_form_layout").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("bsl_symbol_at_position").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("bsl_type_at_position").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("bsl_scope_members").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("bsl_list_methods").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("bsl_get_method_body").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("bsl_analyze_method").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("bsl_module_context").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("bsl_module_exports").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("inspect_platform_reference").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("skill").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("discover_tools").forAllResources() //$NON-NLS-1$
        );
        BASE_READ_PERMISSIONS = Collections.unmodifiableList(rules);
    }

    private final String id;
    private final String name;
    private final String description;
    private final Set<String> allowedTools;
    private final List<PermissionRule> defaultPermissions;
    private final int maxSteps;
    private final long timeoutMs;
    private final boolean readOnly;

    protected GsdPhaseProfile(String id, String name, String description,
            Set<String> allowedTools, List<PermissionRule> defaultPermissions,
            int maxSteps, long timeoutMs, boolean readOnly) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.allowedTools = Collections.unmodifiableSet(new HashSet<>(allowedTools));
        this.defaultPermissions = Collections.unmodifiableList(new ArrayList<>(defaultPermissions));
        this.maxSteps = maxSteps;
        this.timeoutMs = timeoutMs;
        this.readOnly = readOnly;
    }

    protected static List<PermissionRule> extendPermissions(PermissionRule... extra) {
        List<PermissionRule> result = new ArrayList<>(BASE_READ_PERMISSIONS);
        if (extra != null) {
            Collections.addAll(result, extra);
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public final String getId() {
        return id;
    }

    @Override
    public final String getName() {
        return name;
    }

    @Override
    public final String getDescription() {
        return description;
    }

    @Override
    public final Set<String> getAllowedTools() {
        return allowedTools;
    }

    @Override
    public final List<PermissionRule> getDefaultPermissions() {
        return defaultPermissions;
    }

    @Override
    public final int getMaxSteps() {
        return maxSteps;
    }

    @Override
    public final long getTimeoutMs() {
        return timeoutMs;
    }

    @Override
    public final boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public final boolean canExecuteShell() {
        return false;
    }

    @Override
    public final DynamicToolCapability getDynamicToolGrant() {
        return DynamicToolCapability.READ_ONLY;
    }

    @Override
    public String getSystemPromptAddition() {
        ToolRegistry registry = ToolRegistry.getInstance();
        Set<String> effectiveTools = ProfileToolAccess.effectiveToolNames(this, registry);
        String defaultPrompt = AgentPromptTemplates.buildGsdPhasePrompt(id, effectiveTools);
        String overridden = PromptProviderRegistry.getInstance()
                .getSystemPromptAddition(id, defaultPrompt);
        return AgentPromptTemplates.enforceGsdToolParity(
                overridden,
                effectiveTools,
                registry.getAllTools().stream().map(ITool::getName).toList());
    }
}
