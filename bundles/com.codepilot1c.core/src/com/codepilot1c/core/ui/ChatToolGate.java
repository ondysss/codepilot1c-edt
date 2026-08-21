/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import com.codepilot1c.core.agent.profiles.AgentProfile;
import com.codepilot1c.core.agent.profiles.AgentProfileRegistry;
import com.codepilot1c.core.agent.profiles.DynamicToolCapability;
import com.codepilot1c.core.agent.profiles.ProfileToolAccess;
import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.model.ToolDefinition;
import com.codepilot1c.core.permissions.PermissionDenialPayload;
import com.codepilot1c.core.permissions.PermissionRule;
import com.codepilot1c.core.permissions.ProfilePermissionGate;
import com.codepilot1c.core.permissions.ProfilePermissionGate.GateDecision;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolExecutionContext;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.surface.ToolSurfaceContext;

/**
 * Pure profile and permission policy for the ChatView tool loop.
 *
 * <p>The class deliberately has no workbench dependencies. The UI owns dialog
 * lifecycle and rendering, while this policy supplies one parsed argument map,
 * a deterministic decision and the scoped execution context for each call.</p>
 */
public final class ChatToolGate {

    /** Action for a tool call in the chat tool loop. */
    public enum Action {
        /** Return a deterministic permission denial without executing the tool. */
        DENY,
        /** Ask through the existing UI confirmation sink before execution. */
        CONFIRM,
        /** Execute with the approved arguments and scoped context. */
        EXECUTE
    }

    /**
     * Immutable decision for one chat tool call.
     *
     * @param action action selected by the gate
     * @param arguments the single parsed argument map for gating, preview and execution
     * @param context scoped execution context
     * @param denial deterministic payload, non-null only for {@link Action#DENY}
     * @param confirmationUnavailableDenial precomputed payload used if the confirmation sink disappears
     * @param reasonCode machine-readable decision reason or logging marker
     * @param layer winning permission layer
     * @param resource raw strict-gate resource
     */
    public record Decision(
            Action action,
            Map<String, Object> arguments,
            ToolExecutionContext context,
            ToolResult denial,
            ToolResult confirmationUnavailableDenial,
            String reasonCode,
            String layer,
            String resource) {
    }

    private static final String LAYER_PROFILE = "profile"; //$NON-NLS-1$
    private static final String LAYER_TOOL = "tool"; //$NON-NLS-1$

    private final AgentProfile profile;
    private final Supplier<List<PermissionRule>> globalRules;
    private final Function<String, Map<String, Object>> argumentParser;
    private final BooleanSupplier confirmationSinkAvailable;
    private final BooleanSupplier skipConfirmations;
    private final ToolExecutionContext executionContext;

    /**
     * Creates a chat tool gate with all runtime dependencies supplied by the caller.
     *
     * @param profile selected chat profile
     * @param globalRules supplier of global permission rules
     * @param argumentParser parser shared with tool execution
     * @param dynamicToolNames supplier retained for runtime provenance/lifecycle validation;
     *        registration by itself never grants profile capability
     * @param confirmationSinkAvailable whether the UI can currently request confirmation
     * @param skipConfirmations whether confirmation is auto-approved by preference
     */
    public ChatToolGate(
            AgentProfile profile,
            Supplier<List<PermissionRule>> globalRules,
            Function<String, Map<String, Object>> argumentParser,
            Supplier<Set<String>> dynamicToolNames,
            BooleanSupplier confirmationSinkAvailable,
            BooleanSupplier skipConfirmations) {
        this(profile, globalRules, argumentParser, dynamicToolNames,
                confirmationSinkAvailable, skipConfirmations,
                ToolExecutionContext.of(profile, 0));
    }

    /** Creates a gate bound to the immutable execution identity captured for the turn. */
    public ChatToolGate(
            AgentProfile profile,
            Supplier<List<PermissionRule>> globalRules,
            Function<String, Map<String, Object>> argumentParser,
            Supplier<Set<String>> dynamicToolNames,
            BooleanSupplier confirmationSinkAvailable,
            BooleanSupplier skipConfirmations,
            ToolExecutionContext executionContext) {
        this.profile = Objects.requireNonNull(profile, "profile"); //$NON-NLS-1$
        this.globalRules = Objects.requireNonNull(globalRules, "globalRules"); //$NON-NLS-1$
        this.argumentParser = Objects.requireNonNull(argumentParser, "argumentParser"); //$NON-NLS-1$
        Objects.requireNonNull(dynamicToolNames, "dynamicToolNames"); //$NON-NLS-1$
        this.confirmationSinkAvailable = Objects.requireNonNull(
                confirmationSinkAvailable, "confirmationSinkAvailable"); //$NON-NLS-1$
        this.skipConfirmations = Objects.requireNonNull(skipConfirmations, "skipConfirmations"); //$NON-NLS-1$
        this.executionContext = Objects.requireNonNull(executionContext, "executionContext"); //$NON-NLS-1$
        if (!profile.getId().equals(executionContext.parentProfileId())) {
            throw new IllegalArgumentException("execution context profile must match gate profile"); //$NON-NLS-1$
        }
    }

    /**
     * Selects the configured chat profile, falling back to the registry default.
     *
     * @param configuredProfileId configured profile id, blank or null for the default
     * @return selected profile, always non-null
     */
    public static AgentProfile selectProfile(String configuredProfileId) {
        try {
            AgentProfileRegistry registry = AgentProfileRegistry.getInstance();
            if (configuredProfileId != null && !configuredProfileId.isBlank()) {
                return registry.getProfile(configuredProfileId)
                        .orElseGet(registry::getDefaultProfile);
            }
            return registry.getDefaultProfile();
        } catch (Throwable e) {
            return ToolSurfaceContext.defaultProfile();
        }
    }

    /**
     * Returns the profile fixed for the current chat turn.
     *
     * @return selected profile
     */
    public AgentProfile profile() {
        return profile;
    }

    /**
     * Builds the model-facing tool surface from the selected profile.
     * Runtime tools require both a trusted capability classification and the
     * selected profile's explicit runtime grant.
     *
     * @param registry tool registry
     * @return visible tool definitions
     */
    public List<ToolDefinition> visibleToolDefinitions(ToolRegistry registry) {
        Objects.requireNonNull(registry, "registry"); //$NON-NLS-1$
        ToolSurfaceContext context = registry.createRuntimeSurfaceContext(profile);
        List<ToolDefinition> result = new ArrayList<>();
        for (ITool tool : registry.getAllTools()) {
            if (!ProfileToolAccess.allows(profile, tool.getName(), registry)) {
                continue;
            }
            result.add(registry.getToolDefinition(tool, context));
        }
        return List.copyOf(result);
    }

    /**
     * Decides one tool call and parses its arguments exactly once.
     *
     * @param call model tool call
     * @param tool registered tool, or null for the existing unknown-tool contract
     * @return deterministic decision
     */
    public Decision decide(ToolCall call, ITool tool) {
        Objects.requireNonNull(call, "call"); //$NON-NLS-1$
        Map<String, Object> arguments = parseSafe(call.getArguments());
        ToolExecutionContext context = executionContext;
        String toolName = call.getName();

        if (tool == null) {
            return execute(arguments, context, null, "none", null); //$NON-NLS-1$
        }

        if (!ProfileToolAccess.allows(profile, toolName, ToolRegistry.getInstance())) {
            String reasonCode = "tool_not_in_profile"; //$NON-NLS-1$
            return deny(arguments, context, PermissionDenialPayload.denied(
                    toolName, profile.getId(), null, reasonCode, LAYER_PROFILE, null),
                    reasonCode, LAYER_PROFILE, null);
        }

        ProfilePermissionGate.GateResult gate = ProfilePermissionGate.evaluate(
                profile.getDefaultPermissions(), globalRulesSafe(), toolName, arguments);
        String ruleDescription = gate.rule() != null ? gate.rule().getDescription() : null;
        if (gate.isDenied()) {
            String reasonCode = "denied_by_" + gate.layer() + "_rule"; //$NON-NLS-1$ //$NON-NLS-2$
            return deny(arguments, context, PermissionDenialPayload.denied(
                    toolName, profile.getId(), gate.resource(), reasonCode,
                    gate.layer(), ruleDescription),
                    reasonCode, gate.layer(), gate.resource());
        }

        boolean gateAsk = gate.decision() == GateDecision.ASK;
        boolean destructive = tool.isDestructive()
                || ToolRegistry.getInstance().getDynamicToolCapability(toolName)
                        == DynamicToolCapability.MUTATING;
        boolean effectiveConfirmation = gateAsk
                || tool.requiresConfirmation()
                || destructive;
        if (!effectiveConfirmation) {
            return execute(arguments, context, null, gate.layer(), gate.resource());
        }

        if (skipConfirmations.getAsBoolean()) {
            return execute(arguments, context,
                    "confirmation_skipped_by_preference", gate.layer(), gate.resource()); //$NON-NLS-1$
        }

        String unavailableReason = gateAsk
                ? "confirmation_unavailable" //$NON-NLS-1$
                : "confirmation_unavailable_tool_policy"; //$NON-NLS-1$
        String unavailableLayer = gateAsk ? gate.layer() : LAYER_TOOL;
        String unavailableResource = gateAsk ? gate.resource() : null;
        String unavailableDescription = gateAsk ? ruleDescription : null;
        ToolResult unavailable = PermissionDenialPayload.denied(
                toolName, profile.getId(), unavailableResource, unavailableReason,
                unavailableLayer, unavailableDescription);
        if (!confirmationSinkAvailable.getAsBoolean()) {
            return deny(arguments, context, unavailable,
                    unavailableReason, unavailableLayer, unavailableResource);
        }

        String reasonCode = gateAsk
                ? "confirmation_required_by_" + gate.layer() + "_rule" //$NON-NLS-1$ //$NON-NLS-2$
                : "confirmation_required_tool_policy"; //$NON-NLS-1$
        return new Decision(Action.CONFIRM, arguments, context, null, unavailable,
                reasonCode, gateAsk ? gate.layer() : LAYER_TOOL,
                gateAsk ? gate.resource() : null);
    }

    /**
     * Returns whether an approved edit call should be intercepted for diff review.
     * The diff review itself supplies the explicit user approval, so a second
     * confirmation dialog is not required.
     *
     * @param call model tool call
     * @param decision gate decision for the same call
     * @param previewModeEnabled whether diff preview is enabled
     * @return true when the call should be routed to diff review
     */
    public boolean interceptForPreview(
            ToolCall call, Decision decision, boolean previewModeEnabled) {
        Objects.requireNonNull(call, "call"); //$NON-NLS-1$
        Objects.requireNonNull(decision, "decision"); //$NON-NLS-1$
        return previewModeEnabled
                && decision.action() != Action.DENY
                && "edit_file".equals(call.getName()); //$NON-NLS-1$
    }

    private Map<String, Object> parseSafe(String arguments) {
        try {
            Map<String, Object> parsed = argumentParser.apply(arguments);
            return parsed != null ? parsed : Map.of();
        } catch (Throwable e) {
            return Map.of();
        }
    }

    private List<PermissionRule> globalRulesSafe() {
        try {
            List<PermissionRule> rules = globalRules.get();
            return rules != null ? rules : List.of();
        } catch (Throwable e) {
            return List.of();
        }
    }

    private Decision execute(
            Map<String, Object> arguments,
            ToolExecutionContext context,
            String reasonCode,
            String layer,
            String resource) {
        return new Decision(Action.EXECUTE, arguments, context, null, null,
                reasonCode, layer, resource);
    }

    private Decision deny(
            Map<String, Object> arguments,
            ToolExecutionContext context,
            ToolResult denial,
            String reasonCode,
            String layer,
            String resource) {
        return new Decision(Action.DENY, arguments, context, denial, null,
                reasonCode, layer, resource);
    }

}
