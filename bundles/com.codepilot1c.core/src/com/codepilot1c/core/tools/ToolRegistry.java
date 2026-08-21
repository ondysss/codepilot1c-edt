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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
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
import com.codepilot1c.core.agent.profiles.DynamicToolCapability;
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
import com.codepilot1c.core.tools.meta.ToolDescriptor;
import com.codepilot1c.core.tools.meta.ToolDescriptorRegistry;
import com.codepilot1c.core.tools.workspace.*;
import com.codepilot1c.core.tools.gsd.*;
import com.codepilot1c.core.tools.java.JavaCompileProbeTool;

/**
 * Registry for AI tools.
 *
 * <p>Manages tool registration and execution.</p>
 */
public class ToolRegistry {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(ToolRegistry.class);

    private static final String TOOL_PROVIDER_EXTENSION_POINT =
            "com.codepilot1c.core.toolProvider"; //$NON-NLS-1$

    private static final Object INSTANCE_LOCK = new Object();
    private static volatile ToolRegistry instance;
    private static volatile Function<ToolRegistry, List<ITool>> initializationOverride;

    private final Map<String, ITool> tools = new HashMap<>();
    private final Map<String, ITool> dynamicTools = new ConcurrentHashMap<>();
    private volatile Map<String, DynamicToolCapability> dynamicToolCapabilities =
            new ConcurrentHashMap<>();
    private volatile Map<String, ToolSlot> effectiveToolSlots = new HashMap<>();
    private final Gson gson = new Gson();
    private volatile ToolDescriptorRegistry descriptorRegistry =
            ToolDescriptorRegistry.getInstance();
    private final InitializationControl initialization =
            new InitializationControl(Thread.currentThread());
    private ToolArgumentParser argumentParser;
    private ToolExecutionService executionService;
    private volatile ToolSurfaceAugmentor augmentor;

    private ToolRegistry() {
        augmentor = ToolSurfaceAugmentor.passthrough();
        argumentParser = new ToolArgumentParser();
        executionService = new ToolExecutionService(this);
    }

    /**
     * Returns the singleton instance.
     *
     * @return the instance
     */
    public static ToolRegistry getInstance() {
        ToolRegistry current = instance;
        boolean initialize = false;
        if (current == null) {
            synchronized (INSTANCE_LOCK) {
                current = instance;
                if (current == null) {
                    current = new ToolRegistry();
                    instance = current;
                    initialize = true;
                }
            }
        }
        if (initialize) {
            current.initializeSingleton();
        }
        return current.awaitInitialization();
    }

    private void initializeSingleton() {
        ToolDescriptorRegistry.BootstrapLease descriptorBootstrap = null;
        List<RegistrationClaim> claims = List.of();
        try {
            descriptorBootstrap = descriptorRegistry().beginExternalBootstrap();
            Function<ToolRegistry, List<ITool>> override = initializationOverride;
            List<ITool> defaults = override != null
                    ? override.apply(this)
                    : createDefaultTools();
            claims = new ArrayList<>(defaults.size());
            for (ITool tool : defaults) {
                claims.add(installBuiltInStructurally(tool));
            }

            // Authorization is safe once every structural slot has a
            // conservative descriptor. Refinement must never gate callers.
            descriptorBootstrap.structuralReady();
            initialization.structuralReady();
        } catch (Throwable failure) {
            if (descriptorBootstrap != null) {
                descriptorBootstrap.fail(failure);
            }
            initialization.fail(failure);
            throw propagateInitializationFailure(failure);
        }

        // Default-tool refinement is deliberately best-effort. A broken
        // metadata callback leaves the structurally installed default tool
        // visible only through its conservative fail-closed descriptor.
        try {
            for (RegistrationClaim claim : claims) {
                try {
                    refineDescriptor(claim);
                } catch (Throwable failure) {
                    LOG.error("Keeping conservative metadata for default tool: " //$NON-NLS-1$
                            + claim.name(), failure);
                }
            }
            loadToolsFromExtensionPoint();
            augmentor = ToolSurfaceAugmentor.defaultAugmentor();
            LOG.info("ToolRegistry initialized with %d tools", tools.size()); //$NON-NLS-1$
        } catch (Throwable failure) {
            LOG.error("Tool metadata refinement did not complete", failure); //$NON-NLS-1$
        } finally {
            descriptorBootstrap.refinementComplete();
            initialization.refinementComplete();
        }
    }

    private ToolRegistry awaitInitialization() {
        InitializationControl control = initialization;
        if (control == null || control.isOwner(Thread.currentThread())) {
            return this;
        }
        control.awaitCompletion();
        return this;
    }

    private List<ITool> createDefaultTools() {
        List<ITool> defaults = new ArrayList<>();
        // OSS default tools (commodity)
        defaults.add(new ReadFileTool());
        defaults.add(new ListFilesTool());
        defaults.add(new EditFileTool());
        defaults.add(new WriteTool());
        defaults.add(new WorkspaceCopyTransformTool());
        defaults.add(new WorkspaceCopyTransformBatchTool());
        defaults.add(new GrepTool());
        defaults.add(new GlobTool());
        defaults.add(new WorkspaceImportProjectTool());
        defaults.add(new ConnectInfobaseTool());
        defaults.add(new GitInspectTool());
        defaults.add(new GitMutateTool());
        defaults.add(new GitCloneAndImportProjectTool());
        defaults.add(new ImportProjectFromInfobaseTool());
        defaults.add(new EdtContentAssistTool());
        defaults.add(new EdtFindReferencesTool());
        defaults.add(new EdtMetadataDetailsTool());
        defaults.add(new ScanMetadataIndexTool());
        defaults.add(new InspectRoleRightsTool());
        defaults.add(new MutateRoleRightsTool());
        defaults.add(new GetConfigurationPropertiesTool());
        defaults.add(new GetProblemSummaryTool());
        defaults.add(new GetTagsTool());
        defaults.add(new GetObjectsByTagsTool());
        defaults.add(new ListModulesTool());
        defaults.add(new GetModuleStructureTool());
        defaults.add(new SearchInCodeTool());
        defaults.add(new GetMethodCallHierarchyTool());
        defaults.add(new GetProjectCallGraphTool());
        defaults.add(new GoToDefinitionTool());
        defaults.add(new GetSymbolInfoTool());
        defaults.add(new GetBookmarksTool());
        defaults.add(new GetTasksTool());
        defaults.add(new JavaCompileProbeTool());

        defaults.add(new com.codepilot1c.core.tools.profiling.StartProfilingTool());
        defaults.add(new com.codepilot1c.core.tools.profiling.GetProfilingResultsTool());
        defaults.add(new SetBreakpointTool());
        defaults.add(new RemoveBreakpointTool());
        defaults.add(new ListBreakpointsTool());
        defaults.add(new WaitForBreakTool());
        defaults.add(new GetVariablesTool());
        defaults.add(new StepTool());
        defaults.add(new ResumeTool());
        defaults.add(new EvaluateExpressionTool());
        defaults.add(new DebugStatusTool());
        defaults.add(new RunYaxunitTestsTool());
        defaults.add(new DebugYaxunitTestsTool());
        defaults.add(new EdtFieldTypeCandidatesTool());
        defaults.add(new GetPlatformDocumentationTool());
        defaults.add(new BslSymbolAtPositionTool());
        defaults.add(new BslTypeAtPositionTool());
        defaults.add(new BslScopeMembersTool());
        defaults.add(new BslListMethodsTool());
        defaults.add(new BslGetMethodBodyTool());
        defaults.add(new BslAnalyzeMethodTool());
        defaults.add(new BslModuleContextTool());
        defaults.add(new BslModuleExportsTool());
        defaults.add(new ValidateQueryTool());
        defaults.add(new EdtValidateRequestTool());
        defaults.add(new CreateMetadataTool());
        defaults.add(new CreateFormTool());
        defaults.add(new ApplyFormRecipeTool());
        defaults.add(new InspectFormLayoutTool());
        defaults.add(new AddMetadataChildTool());
        defaults.add(new EnsureModuleArtifactTool());
        defaults.add(new UpdateMetadataTool());
        defaults.add(new MutateFormModelTool());
        defaults.add(new DeleteMetadataTool());
        defaults.add(new RenderTemplateTool());
        defaults.add(new InspectTemplateTool());
        defaults.add(new YaxunitAuthoringTool());
        defaults.add(new EdtDiagnosticsTool());
        defaults.add(new GetOneCProcessesTool());
        defaults.add(new GetInfobaseLocksTool());
        defaults.add(new GetStandaloneServerStatusTool());
        defaults.add(new ResolveWebClientUrlTool());
        defaults.add(new GetInfobaseCredentialsTool());
        defaults.add(new TailEdtLogsTool());
        defaults.add(new ExtensionManageTool());
        defaults.add(new MigrateToExtensionNativeTool());
        defaults.add(new EdtExtensionSmokeTool());
        defaults.add(new DcsManageTool());
        defaults.add(new ExternalManageTool());
        defaults.add(new EdtExternalSmokeTool());
        // QaInspectTool dispatches: qa_explain_config, qa_status, qa_steps_search
        defaults.add(new QaInspectTool());
        // QaGenerateTool dispatches: qa_init_config, qa_migrate_config, qa_compile_feature
        defaults.add(new QaGenerateTool());
        // AnalyzeToolErrorTool, EdtUpdateInfobaseTool, EdtLaunchAppTool
        // are now dispatched through EdtDiagnosticsTool
        defaults.add(new com.codepilot1c.core.tools.workspace.UpdateInfobaseStatusTool());
        defaults.add(new QaRunTool());
        defaults.add(new QaPrepareFormContextTool());
        defaults.add(new QaPlanScenarioTool());
        defaults.add(new QaValidateFeatureTool());
        defaults.add(new SkillTool());
        defaults.add(new DelegateToAgentTool(this));
        defaults.add(new TaskTool(this));
        defaults.add(new DiscoverToolsTool(this));
        defaults.add(new com.codepilot1c.core.tools.memory.RememberFactTool());

        // GSD workflow tools
        defaults.add(new GsdGetStateTool());
        defaults.add(new GsdRecordDecisionTool());
        defaults.add(new GsdCreatePlanTool());
        defaults.add(new GsdUpdateTaskTool());
        defaults.add(new GsdRecordEvidenceTool());
        defaults.add(new GsdTransitionTool());
        return defaults;
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
        RegistrationClaim claim = installBuiltInStructurally(tool);
        try {
            refineDescriptor(claim);
        } catch (Throwable failure) {
            rollbackRegistration(claim);
            throw propagateRegistrationFailure(failure);
        }
    }

    /**
     * Unregisters a tool.
     *
     * @param name the tool name
     */
    public void unregister(String name) {
        ToolDescriptorRegistry descriptors = descriptorRegistry();
        ITool revealed = null;
        ToolSlot revealedSlot = null;
        synchronized (this) {
            ToolSlot removed = currentSlot(name);
            if (!tools.containsKey(name)) {
                return;
            }
            ITool dynamic = dynamicTools.get(name);
            if (dynamic != null) {
                DynamicToolCapability capability = dynamicCapabilities()
                        .getOrDefault(name, DynamicToolCapability.NONE);
                revealedSlot = ToolSlot.dynamic(dynamic, capability);
                publishDescriptor(descriptors, removed, revealedSlot,
                        conservativeDescriptor(name));
                tools.remove(name);
                effectiveSlots().put(name, revealedSlot);
                revealed = dynamic;
            } else {
                tools.remove(name);
                effectiveSlots().remove(name);
                if (removed != null) {
                    descriptors.removeSlot(name, removed.identity());
                }
            }
        }
        if (revealed != null) {
            try {
                refineDescriptor(new RegistrationClaim(
                        name, revealed, revealedSlot, null, null,
                        null, null, DynamicToolCapability.NONE, false));
            } catch (Throwable failure) {
                LOG.error("Keeping conservative metadata for revealed dynamic tool: " //$NON-NLS-1$
                        + name, failure);
            }
        }
    }

    /**
     * Registers a dynamic tool (e.g., from MCP server).
     *
     * <p>Dynamic tools are stored separately and can be unregistered at runtime.</p>
     *
     * @param tool the tool to register
     */
    public void registerDynamicTool(ITool tool) {
        registerDynamicTool(tool, DynamicToolCapability.NONE);
    }

    /**
     * Registers a runtime tool with an explicit trusted capability. Runtime
     * lifecycle/provenance alone is not a capability grant; callers that do
     * not classify the tool use {@link DynamicToolCapability#NONE}.
     *
     * @param tool runtime tool
     * @param capability trusted capability classification
     */
    public void registerDynamicTool(
            ITool tool, DynamicToolCapability capability) {
        String name = requireToolName(tool);
        DynamicToolCapability trustedCapability = capability != null
                ? capability : DynamicToolCapability.NONE;
        ToolDescriptorRegistry descriptors = descriptorRegistry();
        RegistrationClaim claim = null;
        synchronized (this) {
            ToolSlot previous = currentSlot(name);
            ITool builtIn = tools.get(name);
            if (builtIn == null) {
                ToolDescriptor previousDescriptor = descriptorBeforeReplacement(
                        descriptors, name, previous);
                ITool previousDynamic = dynamicTools.get(name);
                DynamicToolCapability previousCapability = dynamicCapabilities()
                        .getOrDefault(name, DynamicToolCapability.NONE);
                ToolSlot slot = ToolSlot.dynamic(tool, trustedCapability);
                publishDescriptor(descriptors, previous, slot,
                        conservativeDescriptor(name));
                dynamicTools.put(name, tool);
                dynamicCapabilities().put(name, trustedCapability);
                effectiveSlots().put(name, slot);
                claim = new RegistrationClaim(name, tool, slot, previous,
                        previousDescriptor, null, previousDynamic,
                        previousCapability, false);
            } else {
                dynamicTools.put(name, tool);
                dynamicCapabilities().put(name, trustedCapability);
            }
        }
        if (claim != null) {
            try {
                refineDescriptor(claim);
            } catch (Throwable failure) {
                rollbackRegistration(claim);
                throw propagateRegistrationFailure(failure);
            }
        }
        LOG.debug("Registered dynamic tool: %s (%s)", name, trustedCapability); //$NON-NLS-1$
    }

    /**
     * Unregisters a dynamic tool.
     *
     * @param name the tool name
     */
    public void unregisterDynamicTool(String name) {
        ToolDescriptorRegistry descriptors = descriptorRegistry();
        synchronized (this) {
            ToolSlot removed = currentSlot(name);
            ITool dynamic = dynamicTools.remove(name);
            dynamicCapabilities().remove(name);
            ITool builtIn = tools.get(name);
            if (builtIn != null) {
                currentSlot(name);
            } else if (dynamic != null) {
                effectiveSlots().remove(name);
                if (removed != null) {
                    descriptors.removeSlot(name, removed.identity());
                }
            }
        }
        LOG.debug("Unregistered dynamic tool: %s", name); //$NON-NLS-1$
    }

    /**
     * Unregisters all dynamic tools with names starting with a prefix.
     *
     * @param prefix the prefix
     */
    public void unregisterToolsByPrefix(String prefix) {
        ToolDescriptorRegistry descriptors = descriptorRegistry();
        List<String> toRemove;
        synchronized (this) {
            toRemove = dynamicTools.keySet().stream()
                    .filter(name -> name.startsWith(prefix))
                    .collect(Collectors.toList());
            for (String name : toRemove) {
                ToolSlot removed = currentSlot(name);
                ITool dynamic = dynamicTools.remove(name);
                dynamicCapabilities().remove(name);
                ITool builtIn = tools.get(name);
                if (builtIn != null) {
                    currentSlot(name);
                } else if (dynamic != null) {
                    effectiveSlots().remove(name);
                    if (removed != null) {
                        descriptors.removeSlot(name, removed.identity());
                    }
                }
            }
        }
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
    public synchronized ITool getTool(String name) {
        ToolSlot slot = currentSlot(name);
        return slot != null ? slot.tool() : null;
    }

    /**
     * Returns all registered tools (built-in and dynamic).
     *
     * <p>Built-in tools take precedence over dynamic tools with the same name.</p>
     *
     * @return unmodifiable list of tools
     */
    public synchronized List<ITool> getAllTools() {
        return getAllToolResolutions().stream()
                .map(ToolResolution::tool)
                .toList();
    }

    /**
     * Returns the names of dynamically registered tools (MCP clients and UI contributions).
     *
     * @return immutable set of dynamic tool names
     */
    public synchronized Set<String> getDynamicToolNames() {
        return Set.copyOf(dynamicTools.keySet());
    }

    /**
     * Returns the trusted capability of the effective dynamic tool. A static
     * tool with the same name always wins and therefore returns NONE.
     */
    public synchronized DynamicToolCapability getDynamicToolCapability(String name) {
        ToolSlot slot = currentSlot(name);
        return slot != null && slot.dynamic()
                ? slot.dynamicCapability()
                : DynamicToolCapability.NONE;
    }

    /** Returns whether the effective implementation is runtime-registered. */
    public synchronized boolean isEffectiveDynamicTool(String name) {
        ToolSlot slot = currentSlot(name);
        return slot != null && slot.dynamic();
    }

    /**
     * Resolves one effective registry entry and its capability as an atomic
     * authorization snapshot. Built-ins retain precedence over dynamic tools.
     *
     * @param name tool name
     * @return immutable effective resolution; {@link ToolResolution#tool()} may be null
     */
    public synchronized ToolResolution resolveTool(String name) {
        return resolution(name, currentSlot(name));
    }

    /** Returns an atomic snapshot of every effective registry entry. */
    public synchronized List<ToolResolution> getAllToolResolutions() {
        Map<String, ITool> effectiveOrder = new LinkedHashMap<>();
        effectiveOrder.putAll(dynamicTools);
        effectiveOrder.putAll(tools);
        List<ToolResolution> resolutions = new ArrayList<>(effectiveOrder.size());
        for (String name : effectiveOrder.keySet()) {
            resolutions.add(resolution(name, currentSlot(name)));
        }
        return Collections.unmodifiableList(resolutions);
    }

    /**
     * Atomically claims a resolution only if its opaque slot remains current.
     * The returned exact implementation must be invoked after this method
     * releases the registry monitor.
     */
    synchronized Optional<ToolResolution> dispatchIfCurrent(
            ToolResolution resolution) {
        if (resolution == null || resolution.name() == null
                || resolution.tool() == null || resolution.slotIdentity() == null) {
            return Optional.empty();
        }
        ToolSlot current = currentSlot(resolution.name());
        if (current == null || current.identity() != resolution.slotIdentity()) {
            return Optional.empty();
        }
        return Optional.of(resolution(resolution.name(), current));
    }

    private Map<String, DynamicToolCapability> dynamicCapabilities() {
        if (dynamicToolCapabilities == null) {
            synchronized (this) {
                if (dynamicToolCapabilities == null) {
                    dynamicToolCapabilities = new ConcurrentHashMap<>();
                }
            }
        }
        return dynamicToolCapabilities;
    }

    private Map<String, ToolSlot> effectiveSlots() {
        if (effectiveToolSlots == null) {
            synchronized (this) {
                if (effectiveToolSlots == null) {
                    effectiveToolSlots = new HashMap<>();
                }
            }
        }
        return effectiveToolSlots;
    }

    private ToolSlot currentSlot(String name) {
        if (name == null) {
            return null;
        }
        ITool builtIn = tools.get(name);
        ITool dynamic = dynamicTools.get(name);
        ITool effective = builtIn != null ? builtIn : dynamic;
        boolean isDynamic = builtIn == null && dynamic != null;
        DynamicToolCapability capability = isDynamic
                ? dynamicCapabilities().getOrDefault(name, DynamicToolCapability.NONE)
                : DynamicToolCapability.NONE;
        ToolSlot current = effectiveSlots().get(name);
        if (effective == null) {
            effectiveSlots().remove(name);
            return null;
        }
        if (current != null && current.matches(effective, capability, isDynamic)) {
            return current;
        }
        ToolSlot repaired = new ToolSlot(
                effective, capability, isDynamic, new SlotIdentity());
        ToolDescriptorRegistry descriptors = descriptorRegistry();
        if (!descriptors.publishSlot(
                null, repaired.identity(), conservativeDescriptor(name))) {
            throw new IllegalStateException(
                    "Tool descriptor slot changed while repairing: " + name); //$NON-NLS-1$
        }
        effectiveSlots().put(name, repaired);
        return repaired;
    }

    private ToolResolution resolution(String name, ToolSlot slot) {
        if (slot == null) {
            return new ToolResolution(
                    name, null, DynamicToolCapability.NONE, false, null);
        }
        return new ToolResolution(name, slot.tool(), slot.dynamicCapability(),
                slot.dynamic(), slot.identity());
    }

    /** Exact effective implementation and capability used for authorization. */
    public record ToolResolution(
            String name,
            ITool tool,
            DynamicToolCapability dynamicCapability,
            boolean dynamic,
            SlotIdentity slotIdentity) {
    }

    /** Opaque, non-reusable identity for one current effective registry slot. */
    public static final class SlotIdentity {
        private SlotIdentity() {
        }
    }

    private record ToolSlot(
            ITool tool,
            DynamicToolCapability dynamicCapability,
            boolean dynamic,
            SlotIdentity identity) {

        private static ToolSlot builtIn(ITool tool) {
            return new ToolSlot(tool, DynamicToolCapability.NONE,
                    false, new SlotIdentity());
        }

        private static ToolSlot dynamic(
                ITool tool, DynamicToolCapability capability) {
            return new ToolSlot(tool, capability, true, new SlotIdentity());
        }

        private boolean matches(
                ITool expectedTool, DynamicToolCapability expectedCapability,
                boolean expectedDynamic) {
            return tool == expectedTool
                    && dynamicCapability == expectedCapability
                    && dynamic == expectedDynamic;
        }
    }

    private record RegistrationClaim(
            String name,
            ITool tool,
            ToolSlot slot,
            ToolSlot previousSlot,
            ToolDescriptor previousDescriptor,
            ITool previousBuiltIn,
            ITool previousDynamic,
            DynamicToolCapability previousDynamicCapability,
            boolean builtIn) {
    }

    /**
     * Installs a built-in slot with fail-closed metadata without invoking any
     * tool metadata callback while holding the registry monitor.
     */
    private RegistrationClaim installBuiltInStructurally(ITool tool) {
        String name = requireToolName(tool);
        ToolDescriptorRegistry descriptors = descriptorRegistry();
        synchronized (this) {
            ToolSlot previous = currentSlot(name);
            ToolDescriptor previousDescriptor = descriptorBeforeReplacement(
                    descriptors, name, previous);
            ITool previousBuiltIn = tools.get(name);
            ToolSlot slot = ToolSlot.builtIn(tool);
            publishDescriptor(descriptors, previous, slot,
                    conservativeDescriptor(name));
            tools.put(name, tool);
            effectiveSlots().put(name, slot);
            return new RegistrationClaim(name, tool, slot, previous,
                    previousDescriptor, previousBuiltIn, null,
                    DynamicToolCapability.NONE, true);
        }
    }

    private ToolDescriptor descriptorBeforeReplacement(
            ToolDescriptorRegistry descriptors,
            String name,
            ToolSlot previous) {
        return descriptors.descriptorForReplacement(name,
                previous != null ? previous.identity() : null);
    }

    /**
     * Rolls back only while the failed registration still owns the effective
     * name. A concurrent replacement therefore cannot be removed or obscured
     * by a stale metadata failure.
     */
    private void rollbackRegistration(RegistrationClaim claim) {
        ToolDescriptorRegistry descriptors = descriptorRegistry();
        synchronized (this) {
            ToolSlot current = currentSlot(claim.name());
            if (current == null || current.identity() != claim.slot().identity()) {
                return;
            }

            if (claim.builtIn()) {
                if (claim.previousBuiltIn() != null) {
                    tools.put(claim.name(), claim.previousBuiltIn());
                } else {
                    tools.remove(claim.name());
                }
            } else if (claim.previousDynamic() != null) {
                dynamicTools.put(claim.name(), claim.previousDynamic());
                dynamicCapabilities().put(claim.name(),
                        claim.previousDynamicCapability());
            } else {
                dynamicTools.remove(claim.name());
                dynamicCapabilities().remove(claim.name());
            }

            if (claim.previousSlot() != null) {
                effectiveSlots().put(claim.name(), claim.previousSlot());
            } else {
                effectiveSlots().remove(claim.name());
            }
            ToolDescriptor restoredDescriptor = claim.previousDescriptor();
            if (claim.previousSlot() != null && restoredDescriptor == null) {
                restoredDescriptor = conservativeDescriptor(claim.name());
            }
            descriptors.restoreSlot(
                    claim.name(), claim.slot().identity(),
                    claim.previousSlot() != null
                            ? claim.previousSlot().identity() : null,
                    restoredDescriptor);
        }
    }

    /**
     * Recomputes metadata without holding registry locks, then publishes it
     * only if the exact captured slot is still effective.
     */
    public void refreshToolDescriptors() {
        ToolDescriptorRegistry descriptors = descriptorRegistry();
        for (ToolResolution captured : getAllToolResolutions()) {
            ToolDescriptor descriptor = descriptors.describeTool(captured.tool());
            if (descriptor == null) {
                continue;
            }
            synchronized (this) {
                ToolSlot current = currentSlot(captured.name());
                if (current != null
                        && current.identity() == captured.slotIdentity()) {
                    publishDescriptor(descriptors, current, current, descriptor);
                }
            }
        }
    }

    private void publishDescriptor(
            ToolDescriptorRegistry descriptors,
            ToolSlot previous,
            ToolSlot current,
            ToolDescriptor descriptor) {
        SlotIdentity expected = previous != null ? previous.identity() : null;
        if (!descriptors.publishSlot(expected, current.identity(), descriptor)) {
            throw new IllegalStateException(
                    "Tool descriptor slot changed while publishing: " + descriptor.getName()); //$NON-NLS-1$
        }
    }

    private void refineDescriptor(RegistrationClaim claim) {
        ToolDescriptorRegistry descriptors = descriptorRegistry();
        ToolDescriptor descriptor = requireDescriptor(
                descriptors.describeTool(claim.tool()), claim.name());
        synchronized (this) {
            ToolSlot current = currentSlot(claim.name());
            if (current != null
                    && current.identity() == claim.slot().identity()) {
                publishDescriptor(descriptors, current, current, descriptor);
            }
        }
    }

    private ToolDescriptor conservativeDescriptor(String name) {
        return ToolDescriptor.builder(name)
                .category(com.codepilot1c.core.tools.meta.ToolCategory.OTHER)
                .mutating(true)
                .requiresValidationToken(true)
                .build();
    }

    private String requireToolName(ITool tool) {
        String name = tool != null ? tool.getName() : null;
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "Tool must expose a non-blank stable name: " + name); //$NON-NLS-1$
        }
        return name;
    }

    private ToolDescriptor requireDescriptor(
            ToolDescriptor descriptor, String toolName) {
        if (descriptor == null || toolName == null
                || !toolName.equals(descriptor.getName())) {
            throw new IllegalArgumentException(
                    "Tool must expose a non-blank stable name: " + toolName); //$NON-NLS-1$
        }
        return descriptor;
    }

    private ToolDescriptorRegistry descriptorRegistry() {
        if (descriptorRegistry == null) {
            synchronized (this) {
                if (descriptorRegistry == null) {
                    descriptorRegistry = ToolDescriptorRegistry.createDetached();
                }
            }
        }
        return descriptorRegistry;
    }

    private static RuntimeException propagateInitializationFailure(
            Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("ToolRegistry initialization failed", failure); //$NON-NLS-1$
    }

    private static RuntimeException propagateRegistrationFailure(
            Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Tool registration failed", failure); //$NON-NLS-1$
    }

    private enum InitializationState {
        INITIALIZING,
        STRUCTURAL_READY,
        REFINEMENT_COMPLETE,
        FAILED
    }

    private static final class InitializationControl {
        private final Object stateLock = new Object();
        private final CountDownLatch structuralCompletion = new CountDownLatch(1);
        private volatile InitializationState state = InitializationState.INITIALIZING;
        private volatile Thread owner;
        private volatile Throwable failure;

        private InitializationControl(Thread owner) {
            this.owner = owner;
        }

        private boolean isOwner(Thread thread) {
            InitializationState current = state;
            if (current == InitializationState.FAILED) {
                throw propagateInitializationFailure(failure);
            }
            return current == InitializationState.INITIALIZING
                    && owner == thread;
        }

        private void structuralReady() {
            synchronized (stateLock) {
                if (state != InitializationState.INITIALIZING) {
                    return;
                }
                state = InitializationState.STRUCTURAL_READY;
                owner = null;
            }
            structuralCompletion.countDown();
        }

        private void refinementComplete() {
            synchronized (stateLock) {
                if (state == InitializationState.STRUCTURAL_READY) {
                    state = InitializationState.REFINEMENT_COMPLETE;
                }
            }
        }

        private void fail(Throwable cause) {
            synchronized (stateLock) {
                if (state != InitializationState.INITIALIZING) {
                    return;
                }
                failure = cause;
                state = InitializationState.FAILED;
                owner = null;
            }
            structuralCompletion.countDown();
        }

        private void awaitCompletion() {
            if (state == InitializationState.STRUCTURAL_READY
                    || state == InitializationState.REFINEMENT_COMPLETE) {
                return;
            }
            if (state == InitializationState.FAILED) {
                throw propagateInitializationFailure(failure);
            }
            try {
                structuralCompletion.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted waiting for ToolRegistry initialization", e); //$NON-NLS-1$
            }
            if (state == InitializationState.FAILED) {
                throw propagateInitializationFailure(failure);
            }
        }
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
        return ToolSurfaceContext.builder()
                .profile(profile != null ? profile : ToolSurfaceContext.defaultProfile())
                .build();
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

    /**
     * Executes a tool call with arguments already parsed by the caller.
     *
     * @param toolCall the original tool call
     * @param parameters the exact parsed parameters approved by the permission gate
     * @param traceSession optional trace session
     * @param parentEventId optional parent trace event
     * @return future with the result
     */
    public CompletableFuture<ToolResult> execute(ToolCall toolCall, Map<String, Object> parameters,
            AgentTraceSession traceSession, String parentEventId) {
        return executionService().execute(toolCall, parameters, traceSession, parentEventId);
    }

    /**
     * Executes an already parsed tool call with immutable caller context.
     *
     * @param toolCall original tool call
     * @param parameters exact parsed parameters approved by the permission gate
     * @param traceSession optional trace session
     * @param parentEventId optional parent trace event
     * @param context immutable caller context
     * @return future with the result
     */
    public CompletableFuture<ToolResult> execute(ToolCall toolCall, Map<String, Object> parameters,
            AgentTraceSession traceSession, String parentEventId, ToolExecutionContext context) {
        return executionService().execute(toolCall, parameters, traceSession, parentEventId, context);
    }

    private ToolSurfaceContext contextForTool(
            ITool tool, ToolSurfaceContext baseContext) {
        String name = tool != null ? tool.getName() : null;
        boolean builtIn;
        synchronized (this) {
            builtIn = name != null && tools.containsKey(name);
        }
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
