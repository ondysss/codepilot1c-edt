/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Platform;

import com.codepilot1c.core.evaluation.trace.AgentTraceSession;
import com.google.gson.Gson;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.model.ToolDefinition;
import com.codepilot1c.core.agent.profiles.AgentProfile;
import com.codepilot1c.core.tools.bsl.*;
import com.codepilot1c.core.tools.debug.*;
import com.codepilot1c.core.tools.dcs.*;
import com.codepilot1c.core.tools.diagnostics.*;
import com.codepilot1c.core.tools.extension.*;
import com.codepilot1c.core.tools.external.*;
import com.codepilot1c.core.tools.file.*;
import com.codepilot1c.core.tools.forms.*;
import com.codepilot1c.core.tools.git.*;
import com.codepilot1c.core.tools.metadata.*;
import com.codepilot1c.core.tools.qa.*;
import com.codepilot1c.core.tools.surface.BuiltinToolTaxonomy;
import com.codepilot1c.core.tools.surface.ToolCategory;
import com.codepilot1c.core.tools.surface.ToolSurfaceAugmentor;
import com.codepilot1c.core.tools.surface.ToolSurfaceContext;
import com.codepilot1c.core.tools.meta.DiscoverToolsTool;
import com.codepilot1c.core.tools.meta.ToolDescriptorRegistry;
import com.codepilot1c.core.tools.workspace.*;

/**
 * Registry for AI tools.
 *
 * <p>Manages tool registration and execution.</p>
 */
public class ToolRegistry {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(ToolRegistry.class);

    private static final String TOOL_PROVIDER_EXTENSION_POINT =
            "com.codepilot1c.core.toolProvider"; //$NON-NLS-1$

    private static ToolRegistry instance;

    private final Map<String, ITool> tools = new HashMap<>();
    private final Map<String, ITool> dynamicTools = new ConcurrentHashMap<>();
    private final Gson gson = new Gson();
    private ToolArgumentParser argumentParser;
    private ToolExecutionService executionService;
    private ProviderContextResolver providerContextResolver;
    private volatile ToolSurfaceAugmentor augmentor;

    private ToolRegistry() {
        // Register default tools
        registerDefaultTools();
        augmentor = ToolSurfaceAugmentor.defaultAugmentor();
        argumentParser = new ToolArgumentParser();
        providerContextResolver = new ProviderContextResolver();
        executionService = new ToolExecutionService(this);
        LOG.info("ToolRegistry initialized with %d tools", tools.size()); //$NON-NLS-1$
    }

    /**
     * Returns the singleton instance.
     *
     * @return the instance
     */
    public static synchronized ToolRegistry getInstance() {
        if (instance == null) {
            instance = new ToolRegistry();
        }
        return instance;
    }

    private void registerDefaultTools() {
        // OSS default tools (commodity)
        register(new ReadFileTool());
        register(new ListFilesTool());
        register(new EditFileTool());
        register(new WriteTool());
        register(new GrepTool());
        register(new GlobTool());
        register(new WorkspaceImportProjectTool());
        register(new ConnectInfobaseTool());
        register(new GitInspectTool());
        register(new GitMutateTool());
        register(new GitCloneAndImportProjectTool());
        register(new ImportProjectFromInfobaseTool());
        register(new EdtContentAssistTool());
        register(new EdtFindReferencesTool());
        register(new EdtMetadataDetailsTool());
        register(new ScanMetadataIndexTool());
        register(new GetConfigurationPropertiesTool());
        register(new GetProblemSummaryTool());
        register(new GetTagsTool());
        register(new GetObjectsByTagsTool());
        register(new ListModulesTool());
        register(new GetModuleStructureTool());
        register(new SearchInCodeTool());
        register(new GetMethodCallHierarchyTool());
        register(new GoToDefinitionTool());
        register(new GetSymbolInfoTool());
        register(new GetBookmarksTool());
        register(new GetTasksTool());

        register(new com.codepilot1c.core.tools.profiling.StartProfilingTool());
        register(new com.codepilot1c.core.tools.profiling.GetProfilingResultsTool());
        register(new SetBreakpointTool());
        register(new RemoveBreakpointTool());
        register(new ListBreakpointsTool());
        register(new WaitForBreakTool());
        register(new GetVariablesTool());
        register(new StepTool());
        register(new ResumeTool());
        register(new EvaluateExpressionTool());
        register(new DebugStatusTool());
        register(new RunYaxunitTestsTool());
        register(new DebugYaxunitTestsTool());
        register(new EdtFieldTypeCandidatesTool());
        register(new GetPlatformDocumentationTool());
        register(new BslSymbolAtPositionTool());
        register(new BslTypeAtPositionTool());
        register(new BslScopeMembersTool());
        register(new BslListMethodsTool());
        register(new BslGetMethodBodyTool());
        register(new BslAnalyzeMethodTool());
        register(new BslModuleContextTool());
        register(new BslModuleExportsTool());
        register(new EdtValidateRequestTool());
        register(new CreateMetadataTool());
        register(new CreateFormTool());
        register(new ApplyFormRecipeTool());
        register(new InspectFormLayoutTool());
        register(new AddMetadataChildTool());
        register(new EnsureModuleArtifactTool());
        register(new UpdateMetadataTool());
        register(new MutateFormModelTool());
        register(new DeleteMetadataTool());
        register(new RenderTemplateTool());
        register(new InspectTemplateTool());
        register(new YaxunitAuthoringTool());
        register(new EdtDiagnosticsTool());
        register(new ExtensionManageTool());
        register(new EdtExtensionSmokeTool());
        register(new DcsManageTool());
        register(new ExternalManageTool());
        register(new EdtExternalSmokeTool());
        // QaInspectTool dispatches: qa_explain_config, qa_status, qa_steps_search
        register(new QaInspectTool());
        // QaGenerateTool dispatches: qa_init_config, qa_migrate_config, qa_compile_feature
        register(new QaGenerateTool());
        // AnalyzeToolErrorTool, EdtUpdateInfobaseTool, EdtLaunchAppTool
        // are now dispatched through EdtDiagnosticsTool
        register(new com.codepilot1c.core.tools.workspace.UpdateInfobaseStatusTool());
        register(new QaRunTool());
        register(new QaPrepareFormContextTool());
        register(new QaPlanScenarioTool());
        register(new QaValidateFeatureTool());
        register(new SkillTool());
        register(new DelegateToAgentTool(this));
        register(new TaskTool(this));
        register(new DiscoverToolsTool(this));
        register(new com.codepilot1c.core.tools.memory.RememberFactTool());

        // Extra tools may be contributed by an overlay (e.g. Pro) via extension point.
        loadToolsFromExtensionPoint();
    }

    private void loadToolsFromExtensionPoint() {
        IExtensionRegistry registry = Platform.getExtensionRegistry();
        if (registry == null) {
            return;
        }

        IConfigurationElement[] elements = registry.getConfigurationElementsFor(
                TOOL_PROVIDER_EXTENSION_POINT);
        for (IConfigurationElement element : elements) {
            if (!"tool".equals(element.getName())) { //$NON-NLS-1$
                continue;
            }
            try {
                Object instance = element.createExecutableExtension("class"); //$NON-NLS-1$
                if (instance instanceof ITool tool) {
                    register(tool);
                    LOG.info("Registered tool from extension: %s", tool.getName()); //$NON-NLS-1$
                } else {
                    LOG.warn("Ignoring non-ITool contribution: %s", instance); //$NON-NLS-1$
                }
            } catch (Exception e) {
                LOG.error("Failed to load tool contribution from extension point", e); //$NON-NLS-1$
            }
        }
    }

    /**
     * Registers a tool.
     *
     * @param tool the tool to register
     */
    public void register(ITool tool) {
        tools.put(tool.getName(), tool);
        ToolDescriptorRegistry.getInstance().registerTool(tool);
    }

    /**
     * Unregisters a tool.
     *
     * @param name the tool name
     */
    public void unregister(String name) {
        tools.remove(name);
    }

    /**
     * Registers a dynamic tool (e.g., from MCP server).
     *
     * <p>Dynamic tools are stored separately and can be unregistered at runtime.</p>
     *
     * @param tool the tool to register
     */
    public void registerDynamicTool(ITool tool) {
        dynamicTools.put(tool.getName(), tool);
        ToolDescriptorRegistry.getInstance().registerTool(tool);
        LOG.debug("Registered dynamic tool: %s", tool.getName()); //$NON-NLS-1$
    }

    /**
     * Unregisters a dynamic tool.
     *
     * @param name the tool name
     */
    public void unregisterDynamicTool(String name) {
        dynamicTools.remove(name);
        LOG.debug("Unregistered dynamic tool: %s", name); //$NON-NLS-1$
    }

    /**
     * Unregisters all dynamic tools with names starting with a prefix.
     *
     * @param prefix the prefix
     */
    public void unregisterToolsByPrefix(String prefix) {
        List<String> toRemove = dynamicTools.keySet().stream()
            .filter(name -> name.startsWith(prefix))
            .collect(Collectors.toList());
        toRemove.forEach(dynamicTools::remove);
        if (!toRemove.isEmpty()) {
            LOG.debug("Unregistered %d dynamic tools with prefix: %s", toRemove.size(), prefix); //$NON-NLS-1$
        }
    }

    /**
     * Returns a tool by name.
     *
     * @param name the tool name
     * @return the tool, or null if not found
     */
    public ITool getTool(String name) {
        // Built-in tools take precedence over dynamic tools
        ITool tool = tools.get(name);
        if (tool == null) {
            tool = dynamicTools.get(name);
        }
        return tool;
    }

    /**
     * Returns all registered tools (built-in and dynamic).
     *
     * <p>Built-in tools take precedence over dynamic tools with the same name.</p>
     *
     * @return unmodifiable list of tools
     */
    public List<ITool> getAllTools() {
        Map<String, ITool> allTools = new LinkedHashMap<>();
        // Add dynamic tools first
        allTools.putAll(dynamicTools);
        // Built-in tools override dynamic tools with same name
        allTools.putAll(tools);
        return Collections.unmodifiableList(new ArrayList<>(allTools.values()));
    }

    /**
     * Returns tool definitions for all registered tools (built-in and dynamic).
     *
     * @return list of tool definitions
     */
    public List<ToolDefinition> getToolDefinitions() {
        return getToolDefinitions(ToolSurfaceContext.defaultProfile());
    }

    public List<ToolDefinition> getToolDefinitions(AgentProfile profile) {
        ToolSurfaceContext baseContext = createRuntimeSurfaceContext(profile);
        return getAllTools().stream()
                .map(tool -> getToolDefinition(tool, baseContext))
                .collect(Collectors.toList());
    }

    public List<ToolDefinition> getToolDefinitions(ToolSurfaceContext baseContext) {
        return getAllTools().stream()
                .map(tool -> getToolDefinition(tool, baseContext))
                .collect(Collectors.toList());
    }

    public ToolDefinition getToolDefinition(ITool tool, ToolSurfaceContext baseContext) {
        return effectiveAugmentor().augment(tool, contextForTool(tool, baseContext));
    }

    public ToolSurfaceContext createRuntimeSurfaceContext(AgentProfile profile) {
        return providerContextResolver().createRuntimeSurfaceContext(profile);
    }

    public void setAugmentor(ToolSurfaceAugmentor augmentor) {
        this.augmentor = augmentor != null ? augmentor : ToolSurfaceAugmentor.passthrough();
    }

    public ToolSurfaceAugmentor getAugmentor() {
        return effectiveAugmentor();
    }

    /**
     * Returns the execution service for running tool calls.
     *
     * @return the execution service
     */
    public ToolExecutionService getExecutionService() {
        return executionService();
    }

    /**
     * Executes a tool call.
     *
     * @param toolCall the tool call to execute
     * @return future with the result
     */
    public CompletableFuture<ToolResult> execute(ToolCall toolCall) {
        return executionService().execute(toolCall);
    }

    public CompletableFuture<ToolResult> execute(ToolCall toolCall, AgentTraceSession traceSession,
            String parentEventId) {
        return executionService().execute(toolCall, traceSession, parentEventId);
    }

    private ToolSurfaceContext contextForTool(ITool tool, ToolSurfaceContext baseContext) {
        boolean builtIn = tool != null && tools.containsKey(tool.getName());
        return (baseContext != null ? baseContext : ToolSurfaceContext.passthrough())
                .toBuilder()
                .category(builtIn ? BuiltinToolTaxonomy.categoryOf(tool) : ToolCategory.DYNAMIC)
                .builtIn(builtIn)
                .build();
    }

    private ToolSurfaceAugmentor effectiveAugmentor() {
        if (augmentor == null) {
            augmentor = ToolSurfaceAugmentor.defaultAugmentor();
        }
        return augmentor;
    }

    private ToolExecutionService executionService() {
        if (executionService == null) {
            executionService = new ToolExecutionService(this);
        }
        return executionService;
    }

    private ProviderContextResolver providerContextResolver() {
        if (providerContextResolver == null) {
            providerContextResolver = new ProviderContextResolver();
        }
        return providerContextResolver;
    }

    private ToolArgumentParser argumentParser() {
        if (argumentParser == null) {
            argumentParser = new ToolArgumentParser();
        }
        return argumentParser;
    }

    /**
     * Parses JSON arguments to a map using Gson.
     *
     * <p>This properly handles multiline strings, escape sequences, and nested objects
     * which is critical for SEARCH/REPLACE edit blocks.</p>
     */
    private Map<String, Object> parseArguments(String json) {
        return argumentParser().parseArguments(json);
    }

}
