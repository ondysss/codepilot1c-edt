/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.codepilot1c.core.agent.profiles.AgentCapability;
import com.codepilot1c.core.agent.profiles.AgentProfile;
import com.codepilot1c.core.agent.profiles.AgentProfileRegistry;
import com.codepilot1c.core.agent.profiles.DynamicToolCapability;
import com.codepilot1c.core.evaluation.trace.AgentTraceSession;
import com.codepilot1c.core.evaluation.trace.ArtifactLayout;
import com.codepilot1c.core.mcp.host.prompt.IMcpPromptProvider;
import com.codepilot1c.core.mcp.host.session.McpHostSession;
import com.codepilot1c.core.mcp.model.McpContent;
import com.codepilot1c.core.mcp.model.McpMessage;
import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.permissions.PermissionDenialPayload;
import com.codepilot1c.core.permissions.PermissionManager;
import com.codepilot1c.core.permissions.PermissionRule;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.TaskTool;
import com.codepilot1c.core.tools.ToolExecutionContext;
import com.codepilot1c.core.tools.ToolExecutionService;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import sun.misc.Unsafe;

public class McpHostProfileGateTest {

    private String previousTraceDir;
    private String previousTraceEnabled;
    private Path traceRoot;
    private ToolRegistry registry;
    private ToolRegistry previousRegistry;
    private final List<String> registeredProfileIds = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        previousTraceDir = System.getProperty(ArtifactLayout.PROP_TRACE_DIR);
        previousTraceEnabled = System.getProperty(AgentTraceSession.PROP_TRACE_ENABLED);
        traceRoot = Files.createTempDirectory("mcp-profile-gate-"); //$NON-NLS-1$
        System.setProperty(ArtifactLayout.PROP_TRACE_DIR, traceRoot.toString());
        System.setProperty(AgentTraceSession.PROP_TRACE_ENABLED, Boolean.TRUE.toString());
        registry = isolatedRegistry();
        previousRegistry = installRegistry(registry);
    }

    @After
    public void tearDown() throws Exception {
        for (String profileId : registeredProfileIds) {
            AgentProfileRegistry.getInstance().unregister(profileId);
        }
        installRegistry(previousRegistry);
        restoreProperty(ArtifactLayout.PROP_TRACE_DIR, previousTraceDir);
        restoreProperty(AgentTraceSession.PROP_TRACE_ENABLED, previousTraceEnabled);
    }

    @Test
    public void legacyModeDecisionMatrixMatchesBaseline() {
        for (McpHostConfig.MutationPolicy policy : McpHostConfig.MutationPolicy.values()) {
            for (boolean mutating : List.of(Boolean.FALSE, Boolean.TRUE)) {
                String name = "legacy_matrix_" + policy.name().toLowerCase() + "_" + mutating; //$NON-NLS-1$ //$NON-NLS-2$
                CapturingTool tool = register(new CapturingTool(name, mutating));
                McpMessage response;
                try {
                    response = router(policy, "") //$NON-NLS-1$
                            .route(call(name, new LinkedHashMap<>()), session());
                } catch (LinkageError unavailablePermissionManager) {
                    assertEquals(name, McpHostConfig.MutationPolicy.ASK, policy);
                    assertEquals(name, 0, tool.calls);
                    continue;
                }
                boolean expectedError = policy != McpHostConfig.MutationPolicy.ALLOW;
                assertEquals(name, expectedError, isToolError(response));
                assertEquals(name, expectedError ? 0 : 1, tool.calls);
                if (expectedError) {
                    assertEquals(name, "Tool execution denied by permission policy: " + policy, text(response)); //$NON-NLS-1$
                } else {
                    assertEquals(name, "ok", text(response)); //$NON-NLS-1$
                }
            }
        }
    }

    @Test
    public void legacyModeExecutesToolWhenGlobalRuleWouldAsk() {
        CapturingTool tool = register(new CapturingTool("write_file", true)); //$NON-NLS-1$

        McpMessage response = router(McpHostConfig.MutationPolicy.ALLOW, "") //$NON-NLS-1$
                .route(call(tool.getName(), Map.of("path", "file.txt")), session()); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(isToolError(response));
        assertEquals(1, tool.calls);
    }

    @Test
    public void legacyModeExecutesToolWhenGlobalRuleWouldDeny() {
        CapturingTool tool = register(new CapturingTool("shell", true)); //$NON-NLS-1$

        McpMessage response = router(McpHostConfig.MutationPolicy.ALLOW, "") //$NON-NLS-1$
                .route(call(tool.getName(), Map.of("command", "rm -rf /")), session()); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(isToolError(response));
        assertEquals(1, tool.calls);
    }

    @Test
    public void routerNeverConsultsGlobalPermissionRules() {
        assertFalse(List.of(McpHostRequestRouter.class.getDeclaredMethods()).stream()
                .anyMatch(method -> "globalRulesSafe".equals(method.getName()))); //$NON-NLS-1$
        assertFalse(List.of(McpHostRequestRouter.class.getDeclaredFields()).stream()
                .anyMatch(field -> field.getType() == PermissionManager.class));
        assertFalse(List.of(McpHostRequestRouter.class.getDeclaredConstructors()).stream()
                .flatMap(constructor -> List.of(constructor.getParameterTypes()).stream())
                .anyMatch(type -> type == PermissionManager.class));
    }

    @Test
    public void legacyModeKeepsUnfilteredToolList() {
        register(new CapturingTool("listed_a", false)); //$NON-NLS-1$
        register(new CapturingTool("listed_b", false)); //$NON-NLS-1$
        McpToolExposurePolicy exposure = new NamedExposurePolicy(Set.of("listed_a", "listed_b")); //$NON-NLS-1$ //$NON-NLS-2$

        McpMessage fourArgument = new McpHostRequestRouter(
                exposure, List.of(), prompts(), McpHostConfig.MutationPolicy.ALLOW)
                .route(request("tools/list", Map.of()), session()); //$NON-NLS-1$
        McpMessage explicitLegacy = new McpHostRequestRouter(
                exposure, List.of(), prompts(), McpHostConfig.MutationPolicy.ALLOW, "") //$NON-NLS-1$
                .route(request("tools/list", Map.of()), session()); //$NON-NLS-1$

        assertEquals(new Gson().toJson(fourArgument.getResult()),
                new Gson().toJson(explicitLegacy.getResult()));
        assertEquals(Set.of("listed_a", "listed_b"), listedNames(explicitLegacy)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void mcpRuntimeCapabilitiesRemainUsableAndProfileScoped() {
        CapturingTool read = new CapturingTool("mcp_tracker_search", false); //$NON-NLS-1$
        CapturingTool mutate = new CapturingTool("mcp_tracker_update", true); //$NON-NLS-1$
        CapturingTool unknown = new CapturingTool("mcp_tracker_unknown", false); //$NON-NLS-1$
        registry.registerDynamicTool(read, DynamicToolCapability.READ_ONLY);
        registry.registerDynamicTool(mutate, DynamicToolCapability.MUTATING);
        registry.registerDynamicTool(unknown);
        McpToolExposurePolicy exposure = new NamedExposurePolicy(Set.of(
                read.getName(), mutate.getName(), unknown.getName()));

        Set<String> gsdNames = listedNames(router(
                exposure, McpHostConfig.MutationPolicy.ALLOW, "gsd-discuss") //$NON-NLS-1$
                .route(request("tools/list", Map.of()), session())); //$NON-NLS-1$
        assertTrue(gsdNames.contains(read.getName()));
        assertFalse(gsdNames.contains(mutate.getName()));
        assertFalse(gsdNames.contains(unknown.getName()));

        Set<String> buildNames = listedNames(router(
                exposure, McpHostConfig.MutationPolicy.ALLOW, "build") //$NON-NLS-1$
                .route(request("tools/list", Map.of()), session())); //$NON-NLS-1$
        assertTrue(buildNames.contains(read.getName()));
        assertTrue(buildNames.contains(mutate.getName()));
        assertFalse(buildNames.contains(unknown.getName()));
    }

    @Test
    public void dynamicMutatingNoRuleIsRejectedUnderAllowWithMachineReadableReason() {
        CapturingTool tool = new CapturingTool("mcp_adversary_update", false); //$NON-NLS-1$
        registry.registerDynamicTool(tool, DynamicToolCapability.MUTATING);

        McpMessage response = router(
                new NamedExposurePolicy(Set.of(tool.getName())),
                McpHostConfig.MutationPolicy.ALLOW, "build") //$NON-NLS-1$
                .route(call(tool.getName(), Map.of("value", "changed")), session()); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(isToolError(response));
        assertEquals(0, tool.calls);
        JsonObject denial = structuredContent(response);
        assertEquals("confirmation_unavailable_tool_policy", //$NON-NLS-1$
                denial.get("reason_code").getAsString()); //$NON-NLS-1$
        assertEquals("tool", denial.get("layer").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("build", denial.get("profile").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void trustedDynamicReadOnlyToolStillExecutesUnderAllow() {
        CapturingTool tool = new CapturingTool("mcp_tracker_search", false); //$NON-NLS-1$
        registry.registerDynamicTool(tool, DynamicToolCapability.READ_ONLY);

        McpMessage response = router(
                new NamedExposurePolicy(Set.of(tool.getName())),
                McpHostConfig.MutationPolicy.ALLOW, "build") //$NON-NLS-1$
                .route(call(tool.getName(), Map.of("query", "open")), session()); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(isToolError(response));
        assertEquals("ok", text(response)); //$NON-NLS-1$
        assertEquals(1, tool.calls);
    }

    @Test
    public void toolAndExposureConfirmationSignalsFailClosedUnderAllow() {
        CapturingTool confirming = new CapturingTool(
                "mcp_local_confirming", false, true, false); //$NON-NLS-1$
        CapturingTool destructive = new CapturingTool(
                "mcp_local_destructive", false, false, true); //$NON-NLS-1$
        CapturingTool policyConfirmed = new CapturingTool(
                "mcp_policy_confirming", false); //$NON-NLS-1$
        registry.registerDynamicTool(confirming, DynamicToolCapability.READ_ONLY);
        registry.registerDynamicTool(destructive, DynamicToolCapability.READ_ONLY);
        registry.registerDynamicTool(policyConfirmed, DynamicToolCapability.READ_ONLY);

        McpMessage confirmingResponse = router(
                new NamedExposurePolicy(Set.of(confirming.getName())),
                McpHostConfig.MutationPolicy.ALLOW, "build") //$NON-NLS-1$
                .route(call(confirming.getName(), Map.of()), session());
        McpMessage destructiveResponse = router(
                new NamedExposurePolicy(Set.of(destructive.getName())),
                McpHostConfig.MutationPolicy.ALLOW, "build") //$NON-NLS-1$
                .route(call(destructive.getName(), Map.of()), session());
        McpHostRequestRouter policyRouter = router(
                new ConfirmingExposurePolicy(policyConfirmed.getName()),
                McpHostConfig.MutationPolicy.ALLOW, "build"); //$NON-NLS-1$
        Map<String, Object> policyMetadata = listedTool(policyRouter.route(
                request("tools/list", Map.of()), session()), policyConfirmed.getName()); //$NON-NLS-1$
        McpMessage policyResponse = policyRouter.route(
                call(policyConfirmed.getName(), Map.of()), session());

        for (McpMessage response : List.of(
                confirmingResponse, destructiveResponse, policyResponse)) {
            assertTrue(isToolError(response));
            assertEquals("confirmation_unavailable_tool_policy", //$NON-NLS-1$
                    structuredContent(response).get("reason_code").getAsString()); //$NON-NLS-1$
        }
        assertEquals(0, confirming.calls);
        assertEquals(0, destructive.calls);
        assertEquals(0, policyConfirmed.calls);
        assertEquals(Boolean.TRUE, metadata(policyMetadata)
                .get("codepilot1c/requiresConfirmation")); //$NON-NLS-1$
    }

    @Test
    public void toolListMetadataUsesEffectiveDynamicCapability() {
        CapturingTool read = new CapturingTool("mcp_metadata_read", false); //$NON-NLS-1$
        CapturingTool mutate = new CapturingTool("mcp_metadata_update", false); //$NON-NLS-1$
        registry.registerDynamicTool(read, DynamicToolCapability.READ_ONLY);
        registry.registerDynamicTool(mutate, DynamicToolCapability.MUTATING);
        McpToolExposurePolicy exposure = new NamedExposurePolicy(
                Set.of(read.getName(), mutate.getName()));

        McpMessage response = router(
                exposure, McpHostConfig.MutationPolicy.ALLOW, "build") //$NON-NLS-1$
                .route(request("tools/list", Map.of()), session()); //$NON-NLS-1$
        Map<String, Object> readMetadata = listedTool(response, read.getName());
        Map<String, Object> mutateMetadata = listedTool(response, mutate.getName());

        assertEquals(Boolean.TRUE, annotations(readMetadata).get("readOnlyHint")); //$NON-NLS-1$
        assertFalse(readMetadata.containsKey("_meta")); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, annotations(mutateMetadata).get("destructiveHint")); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, metadata(mutateMetadata)
                .get("codepilot1c/requiresConfirmation")); //$NON-NLS-1$
    }

    @Test
    public void builtInCollisionKeepsBuiltInExecutionAndMetadata() {
        String name = "mcp_collision"; //$NON-NLS-1$
        CapturingTool builtIn = register(new CapturingTool(name, false));
        CapturingTool dynamic = new CapturingTool(name, false);
        registry.registerDynamicTool(dynamic, DynamicToolCapability.MUTATING);
        McpToolExposurePolicy exposure = new NamedExposurePolicy(Set.of(name));
        McpHostRequestRouter router = router(
                exposure, McpHostConfig.MutationPolicy.ALLOW, ""); //$NON-NLS-1$

        Map<String, Object> listed = listedTool(router.route(
                request("tools/list", Map.of()), session()), name); //$NON-NLS-1$
        McpMessage response = router.route(call(name, Map.of()), session());

        assertSame(builtIn, registry.getTool(name));
        assertFalse(listed.containsKey("annotations")); //$NON-NLS-1$
        assertFalse(listed.containsKey("_meta")); //$NON-NLS-1$
        assertFalse(isToolError(response));
        assertEquals(1, builtIn.calls);
        assertEquals(0, dynamic.calls);
    }

    @Test
    public void configuredProfileNarrowsToolListWithoutWideningExposure() {
        register(new CapturingTool("profile_visible", false)); //$NON-NLS-1$
        register(new CapturingTool("profile_disallowed", false)); //$NON-NLS-1$
        register(new CapturingTool("profile_sensitive", false)); //$NON-NLS-1$
        String profileId = registerProfile(Set.of("profile_visible", "profile_sensitive"), //$NON-NLS-1$ //$NON-NLS-2$
                List.of(), false);
        McpHostConfig config = McpHostConfig.defaults();
        config.setExposedToolsFilter("*"); //$NON-NLS-1$
        McpToolExposurePolicy exposure = new DefaultMcpToolExposurePolicy(
                config, "profile_sensitive"::equals); //$NON-NLS-1$

        McpMessage response = router(exposure, McpHostConfig.MutationPolicy.ALLOW, profileId)
                .route(request("tools/list", Map.of()), session()); //$NON-NLS-1$

        assertEquals(Set.of("profile_visible"), listedNames(response)); //$NON-NLS-1$
    }

    @Test
    public void emptyAllowlistExposesNoStaticTools() {
        CapturingTool tool = register(new CapturingTool("empty_profile_static", false)); //$NON-NLS-1$
        String profileId = registerProfile(Set.of(), List.of(), true);
        McpToolExposurePolicy exposure = new NamedExposurePolicy(Set.of(tool.getName()));
        McpHostRequestRouter router = router(
                exposure, McpHostConfig.MutationPolicy.ALLOW, profileId);

        assertFalse(listedNames(router.route(
                request("tools/list", Map.of()), session())).contains(tool.getName())); //$NON-NLS-1$
        McpMessage response = router.route(call(tool.getName(), Map.of()), session());
        assertTrue(text(response).contains("reason_code=tool_not_in_profile")); //$NON-NLS-1$
        assertEquals(0, tool.calls);
    }

    @Test
    public void mutatingToolIsDeniedUnderReadOnlyMcpProfile() {
        CapturingTool tool = register(new CapturingTool("profile_mutation", true)); //$NON-NLS-1$
        String profileId = registerProfile(Set.of(tool.getName()),
                List.of(PermissionRule.deny(tool.getName()).forAllResources()), true);

        McpMessage response = router(McpHostConfig.MutationPolicy.ALLOW, profileId)
                .route(call(tool.getName(), Map.of()), session());

        assertTrue(isToolError(response));
        assertTrue(text(response).contains("reason_code=denied_by_profile_rule")); //$NON-NLS-1$
        assertEquals(0, tool.calls);
    }

    @Test
    public void toolNotInConfiguredProfileIsDeniedBeforeExecution() {
        CapturingTool tool = register(new CapturingTool("not_allowed", false)); //$NON-NLS-1$
        String profileId = registerProfile(Set.of("another_tool"), List.of(), false); //$NON-NLS-1$

        McpMessage response = router(McpHostConfig.MutationPolicy.ALLOW, profileId)
                .route(call(tool.getName(), Map.of()), session());

        assertTrue(text(response).contains("reason_code=tool_not_in_profile")); //$NON-NLS-1$
        assertTrue(text(response).contains("layer=profile")); //$NON-NLS-1$
        assertEquals(0, tool.calls);
    }

    @Test
    public void profileDenyRuleProducesDeterministicPayload() {
        CapturingTool tool = register(new CapturingTool("profile_rule_deny", true)); //$NON-NLS-1$
        PermissionRule rule = PermissionRule.deny(tool.getName())
                .withDescription("blocked by test") //$NON-NLS-1$
                .forResourcePattern("src/**").build(); //$NON-NLS-1$
        String profileId = registerProfile(Set.of(tool.getName()), List.of(rule), false);

        McpMessage first = router(McpHostConfig.MutationPolicy.ALLOW, profileId)
                .route(call(tool.getName(), Map.of("path", "src/file.txt")), session()); //$NON-NLS-1$ //$NON-NLS-2$
        McpMessage second = router(McpHostConfig.MutationPolicy.ALLOW, profileId)
                .route(call(tool.getName(), Map.of("path", "src/file.txt")), session()); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(text(first), text(second));
        assertTrue(text(first).contains("resource=src/file.txt")); //$NON-NLS-1$
        assertTrue(text(first).contains("reason_code=denied_by_profile_rule")); //$NON-NLS-1$
        assertTrue(text(first).contains("rule_description=blocked by test")); //$NON-NLS-1$
    }

    @Test
    public void resourceScopedProfileRuleAppliesToMutatingEdtTool() {
        CapturingTool tool = register(new CapturingTool("create_metadata", true)); //$NON-NLS-1$
        PermissionRule rule = PermissionRule.deny(tool.getName())
                .forResourcePattern("Catalog.*").build(); //$NON-NLS-1$
        String profileId = registerProfile(Set.of(tool.getName()), List.of(rule), false);

        McpMessage response = router(McpHostConfig.MutationPolicy.ALLOW, profileId)
                .route(call(tool.getName(), Map.of("target_fqn", "Catalog.Products")), session()); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(text(response).contains("resource=Catalog.Products")); //$NON-NLS-1$
        assertTrue(text(response).contains("reason_code=denied_by_profile_rule")); //$NON-NLS-1$
        assertEquals(0, tool.calls);
    }

    @Test
    public void profileGateIsNotWeakenedByAllowMutationPolicy() {
        CapturingTool tool = register(new CapturingTool("strict_profile_deny", true)); //$NON-NLS-1$
        String profileId = registerProfile(Set.of(tool.getName()),
                List.of(PermissionRule.deny(tool.getName()).forAllResources()), false);

        McpMessage response = router(McpHostConfig.MutationPolicy.ALLOW, profileId)
                .route(call(tool.getName(), Map.of()), session());

        assertTrue(isToolError(response));
        assertEquals(0, tool.calls);
    }

    @Test
    public void mutationPolicyDenyIsNotWeakenedByProfileAllow() {
        CapturingTool tool = register(new CapturingTool("strict_policy_deny", true)); //$NON-NLS-1$
        String profileId = registerProfile(Set.of(tool.getName()),
                List.of(PermissionRule.allow(tool.getName()).forAllResources()), false);

        McpMessage response = router(McpHostConfig.MutationPolicy.DENY, profileId)
                .route(call(tool.getName(), Map.of()), session());

        assertEquals("Tool execution denied by permission policy: DENY", text(response)); //$NON-NLS-1$
        assertEquals(0, tool.calls);
    }

    @Test
    public void profileAskIsFailClosedWithoutConfirmationSink() throws Exception {
        CapturingTool tool = register(new CapturingTool("profile_ask", true)); //$NON-NLS-1$
        String profileId = registerProfile(Set.of(tool.getName()),
                List.of(PermissionRule.ask(tool.getName()).forAllResources()), false);

        McpMessage response = CompletableFuture.supplyAsync(() ->
                router(McpHostConfig.MutationPolicy.ALLOW, profileId)
                        .route(call(tool.getName(), Map.of()), session()))
                .get(1, TimeUnit.SECONDS);

        assertTrue(text(response).contains("reason_code=confirmation_unavailable")); //$NON-NLS-1$
        assertEquals(0, tool.calls);
    }

    @Test
    public void unknownConfiguredProfileFailsClosed() {
        CapturingTool tool = register(new CapturingTool("unknown_profile_tool", false)); //$NON-NLS-1$
        McpHostRequestRouter router = router(McpHostConfig.MutationPolicy.ALLOW,
                "no-such-profile"); //$NON-NLS-1$

        McpMessage callResponse = router.route(call(tool.getName(), Map.of()), session());
        McpMessage listResponse = router.route(request("tools/list", Map.of()), session()); //$NON-NLS-1$

        assertTrue(text(callResponse).contains("reason_code=profile_unresolved")); //$NON-NLS-1$
        assertEquals(0, tool.calls);
        assertTrue(listedNames(listResponse).isEmpty());
    }

    @Test
    public void profileDenialPayloadMatchesAgentRunnerKeySet() {
        ToolResult withoutResource = PermissionDenialPayload.denied(
                "tool", "profile", null, "reason", "profile", null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        ToolResult withResource = PermissionDenialPayload.denied(
                "tool", "profile", "resource", "reason", "profile", "rule"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

        assertEquals(Set.of("error", "tool", "profile", "reason", "reason_code", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "layer", "rule_description"), withoutResource.getStructuredData().keySet()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Set.of("error", "tool", "profile", "reason", "reason_code", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "layer", "rule_description", "resource"), withResource.getStructuredData().keySet()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void mcpExecutionCarriesScopedExecutionContext() {
        CapturingTool legacyTool = register(new CapturingTool("legacy_context", false)); //$NON-NLS-1$
        router(McpHostConfig.MutationPolicy.ALLOW, "") //$NON-NLS-1$
                .route(call(legacyTool.getName(), Map.of()), session());

        assertNotNull(legacyTool.context);
        assertTrue(legacyTool.context.isScoped());
        assertEquals("mcp-host", legacyTool.context.parentProfileId()); //$NON-NLS-1$
        assertEquals(AgentCapability.MUTATING, legacyTool.context.delegationCeiling());
        assertEquals(0, legacyTool.context.delegationDepth());

        CapturingTool profileTool = register(new CapturingTool("profile_context", false)); //$NON-NLS-1$
        String profileId = registerProfile(Set.of(profileTool.getName()), List.of(), true);
        router(McpHostConfig.MutationPolicy.ALLOW, profileId)
                .route(call(profileTool.getName(), Map.of()), session());

        assertEquals(profileId, profileTool.context.parentProfileId());
        assertEquals(AgentCapability.READ_ONLY, profileTool.context.delegationCeiling());
        assertEquals(0, profileTool.context.delegationDepth());
    }

    @Test
    public void gateAndExecutionShareOneParsedArgumentMap() {
        CapturingTool tool = register(new CapturingTool("same_map", false)); //$NON-NLS-1$
        LinkedHashMap<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("value", "same"); //$NON-NLS-1$ //$NON-NLS-2$
        String profileId = registerProfile(Set.of(tool.getName()),
                List.of(PermissionRule.allow(tool.getName()).forAllResources()), false);

        router(McpHostConfig.MutationPolicy.ALLOW, profileId)
                .route(call(tool.getName(), arguments), session());

        assertSame(arguments, tool.parameters);
    }

    @Test
    public void mcpToolTraceKeyVocabularyIsUnchanged() throws Exception {
        Set<String> vocabulary = Set.of(
                "method", "tool_name", "arguments", "permission_decision", "duration_ms", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "success", "result_type", "content", "error_message", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "exception_type", "exception_message"); //$NON-NLS-1$ //$NON-NLS-2$

        CapturingTool success = register(new CapturingTool("trace_success", false)); //$NON-NLS-1$
        CapturingTool failure = register(new CapturingTool("trace_failure", false)); //$NON-NLS-1$
        failure.result = ToolResult.failure("expected"); //$NON-NLS-1$
        CapturingTool throwing = register(new CapturingTool("trace_throwing", false)); //$NON-NLS-1$
        throwing.future = CompletableFuture.failedFuture(new IllegalStateException("boom")); //$NON-NLS-1$
        CapturingTool layerB = register(new CapturingTool("trace_layer_b", false)); //$NON-NLS-1$
        CapturingTool layerA = register(new CapturingTool("trace_layer_a", false)); //$NON-NLS-1$
        String profileId = registerProfile(Set.of(layerA.getName()),
                List.of(PermissionRule.deny(layerA.getName()).forAllResources()), false);

        List<TraceRun> runs = List.of(
                traceCall(router(McpHostConfig.MutationPolicy.ALLOW, ""), success), //$NON-NLS-1$
                traceCall(router(McpHostConfig.MutationPolicy.ALLOW, ""), failure), //$NON-NLS-1$
                traceCall(router(McpHostConfig.MutationPolicy.DENY, ""), layerB), //$NON-NLS-1$
                traceCall(router(McpHostConfig.MutationPolicy.ALLOW, profileId), layerA),
                traceCall(router(McpHostConfig.MutationPolicy.ALLOW, ""), throwing)); //$NON-NLS-1$

        for (TraceRun run : runs) {
            JsonObject data = toolTraceData(run.session(), run.toolName());
            assertTrue(run.toolName() + ": " + data.keySet(), //$NON-NLS-1$
                    vocabulary.containsAll(data.keySet()));
        }
    }

    @Test
    public void mcpToolCallWritesNoToolCallOrToolResultTraceEvents() throws Exception {
        CapturingTool tool = register(new CapturingTool("no_tool_events", false)); //$NON-NLS-1$
        McpHostSession session = tracedSession("no-tool-events"); //$NON-NLS-1$

        router(McpHostConfig.MutationPolicy.ALLOW, "") //$NON-NLS-1$
                .route(call(tool.getName(), Map.of()), session);

        Path toolsFile = session.getTraceSession().getLayout().getToolsFile();
        assertTrue(!Files.exists(toolsFile) || Files.readAllLines(toolsFile).isEmpty());
    }

    @Test
    public void legacyDenyEnvelopeIsByteIdentical() {
        CapturingTool tool = register(new CapturingTool("legacy_deny", true)); //$NON-NLS-1$

        McpMessage response = router(McpHostConfig.MutationPolicy.DENY, "") //$NON-NLS-1$
                .route(call(tool.getName(), Map.of()), session());

        assertEquals("Tool execution denied by permission policy: DENY", text(response)); //$NON-NLS-1$
    }

    @Test
    public void toolTimeoutStillProducesRouterExceptionEnvelope() throws Exception {
        CapturingTool tool = register(new CapturingTool("forced_timeout", false)); //$NON-NLS-1$
        setField(registry, "executionService", new TimeoutExecutionService(registry)); //$NON-NLS-1$
        McpHostSession session = tracedSession("forced-timeout"); //$NON-NLS-1$

        McpMessage response = router(McpHostConfig.MutationPolicy.ALLOW, "") //$NON-NLS-1$
                .route(call(tool.getName(), Map.of()), session);

        assertTrue(text(response).startsWith("Tool execution failed: ")); //$NON-NLS-1$
        JsonObject trace = toolTraceData(session, tool.getName());
        assertTrue(trace.has("exception_type")); //$NON-NLS-1$
        assertTrue(trace.has("exception_message")); //$NON-NLS-1$
    }

    @Test
    public void unresolvableDelegateTargetIsDeniedUnderScopedContext() {
        TaskTool task = new TaskTool(registry);
        registry.registerDynamicTool(task);

        McpMessage response = router(McpHostConfig.MutationPolicy.ALLOW, "") //$NON-NLS-1$
                .route(call(task.getName(), Map.of(
                        "prompt", "inspect", //$NON-NLS-1$ //$NON-NLS-2$
                        "profile", "missing-profile")), session()); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(text(response).contains("reason_code=delegation_target_unresolved")); //$NON-NLS-1$
        assertTrue(text(response).contains("parent=mcp-host")); //$NON-NLS-1$
    }

    @Test
    public void resolvableDelegateTargetIsUnaffectedByScopedContext() {
        TaskTool task = new TaskTool(registry);
        registry.registerDynamicTool(task);

        McpMessage response = router(McpHostConfig.MutationPolicy.ALLOW, "") //$NON-NLS-1$
                .route(call(task.getName(), Map.of(
                        "prompt", "inspect", //$NON-NLS-1$ //$NON-NLS-2$
                        "profile", "explore")), session()); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(text(response).contains("delegation_target_unresolved")); //$NON-NLS-1$
        assertFalse(text(response).contains("delegation_capability_exceeded")); //$NON-NLS-1$
    }

    private TraceRun traceCall(McpHostRequestRouter router, CapturingTool tool) {
        McpHostSession session = tracedSession(tool.getName());
        router.route(call(tool.getName(), Map.of()), session);
        return new TraceRun(session, tool.getName());
    }

    private JsonObject toolTraceData(McpHostSession session, String toolName) throws IOException {
        return readJsonLines(session.getTraceSession().getLayout().getMcpFile()).stream()
                .map(event -> event.getAsJsonObject("data")) //$NON-NLS-1$
                .filter(data -> data.has("tool_name")) //$NON-NLS-1$
                .filter(data -> toolName.equals(data.get("tool_name").getAsString())) //$NON-NLS-1$
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing tool trace: " + toolName)); //$NON-NLS-1$
    }

    private McpHostRequestRouter router(McpHostConfig.MutationPolicy policy, String profileId) {
        return router(new AllowAllExposurePolicy(), policy, profileId);
    }

    private McpHostRequestRouter router(
            McpToolExposurePolicy exposure, McpHostConfig.MutationPolicy policy,
            String profileId) {
        return new McpHostRequestRouter(exposure, List.of(), prompts(), policy, profileId);
    }

    private IMcpPromptProvider prompts() {
        return new IMcpPromptProvider() {
            @Override
            public List<com.codepilot1c.core.mcp.model.McpPrompt> listPrompts() {
                return List.of();
            }

            @Override
            public java.util.Optional<com.codepilot1c.core.mcp.model.McpPromptResult> getPrompt(
                    String name, Map<String, Object> arguments) {
                return java.util.Optional.empty();
            }
        };
    }

    private String registerProfile(
            Set<String> allowedTools, List<PermissionRule> rules, boolean readOnly) {
        String id = "mcp-test-profile-" + registeredProfileIds.size(); //$NON-NLS-1$
        AgentProfileRegistry.getInstance().register(new StaticProfile(id, allowedTools, rules, readOnly));
        registeredProfileIds.add(id);
        return id;
    }

    private CapturingTool register(CapturingTool tool) {
        registry.register(tool);
        return tool;
    }

    private McpHostSession session() {
        McpHostSession session = new McpHostSession("mcp-profile-test"); //$NON-NLS-1$
        session.setClientName("test-client"); //$NON-NLS-1$
        return session;
    }

    private McpHostSession tracedSession(String id) {
        McpHostSession session = session();
        session.setTraceSession(AgentTraceSession.startMcpSession(
                id, "test", "local", "/mcp")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(session.getTraceSession());
        return session;
    }

    private McpMessage call(String toolName, Map<String, Object> arguments) {
        return request("tools/call", Map.of("name", toolName, "arguments", arguments)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private McpMessage request(String method, Object params) {
        McpMessage request = new McpMessage();
        request.setMethod(method);
        request.setRawId(method + "-id"); //$NON-NLS-1$
        request.setParams(params);
        return request;
    }

    @SuppressWarnings("unchecked")
    private boolean isToolError(McpMessage response) {
        return Boolean.TRUE.equals(((Map<String, Object>) response.getResult()).get("isError")); //$NON-NLS-1$
    }

    @SuppressWarnings("unchecked")
    private String text(McpMessage response) {
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        List<McpContent> content = (List<McpContent>) result.get("content"); //$NON-NLS-1$
        return content.get(0).getText();
    }

    @SuppressWarnings("unchecked")
    private JsonObject structuredContent(McpMessage response) {
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        return (JsonObject) result.get("structuredContent"); //$NON-NLS-1$
    }

    @SuppressWarnings("unchecked")
    private Set<String> listedNames(McpMessage response) {
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools"); //$NON-NLS-1$
        Set<String> names = new HashSet<>();
        for (Map<String, Object> tool : tools) {
            names.add(String.valueOf(tool.get("name"))); //$NON-NLS-1$
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> listedTool(McpMessage response, String name) {
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools"); //$NON-NLS-1$
        return tools.stream()
                .filter(tool -> name.equals(tool.get("name"))) //$NON-NLS-1$
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing listed tool: " + name)); //$NON-NLS-1$
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> annotations(Map<String, Object> tool) {
        return (Map<String, Object>) tool.get("annotations"); //$NON-NLS-1$
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metadata(Map<String, Object> tool) {
        return (Map<String, Object>) tool.get("_meta"); //$NON-NLS-1$
    }

    private List<JsonObject> readJsonLines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .map(line -> JsonParser.parseString(line).getAsJsonObject())
                .toList();
    }

    private void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static ToolRegistry isolatedRegistry() throws Exception {
        ToolRegistry registry = (ToolRegistry) unsafe().allocateInstance(ToolRegistry.class);
        setField(registry, "tools", new HashMap<String, ITool>()); //$NON-NLS-1$
        setField(registry, "dynamicTools", new HashMap<String, ITool>()); //$NON-NLS-1$
        setField(registry, "dynamicToolCapabilities", //$NON-NLS-1$
                new HashMap<String, DynamicToolCapability>());
        setField(registry, "gson", new Gson()); //$NON-NLS-1$
        return registry;
    }

    private static ToolRegistry installRegistry(ToolRegistry registry) throws Exception {
        Field field = ToolRegistry.class.getDeclaredField("instance"); //$NON-NLS-1$
        field.setAccessible(true);
        ToolRegistry previous = (ToolRegistry) field.get(null);
        field.set(null, registry);
        return previous;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); //$NON-NLS-1$
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private record TraceRun(McpHostSession session, String toolName) {
    }

    private static final class CapturingTool implements ITool {

        private final String name;
        private final boolean mutating;
        private final boolean requiresConfirmation;
        private final boolean destructive;
        private int calls;
        private Map<String, Object> parameters;
        private ToolExecutionContext context;
        private ToolResult result = ToolResult.success("ok"); //$NON-NLS-1$
        private CompletableFuture<ToolResult> future;

        private CapturingTool(String name, boolean mutating) {
            this(name, mutating, false, false);
        }

        private CapturingTool(
                String name, boolean mutating, boolean requiresConfirmation,
                boolean destructive) {
            this.name = name;
            this.mutating = mutating;
            this.requiresConfirmation = requiresConfirmation;
            this.destructive = destructive;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "MCP profile gate test tool"; //$NON-NLS-1$
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            return execute(parameters, ToolExecutionContext.unscoped());
        }

        @Override
        public CompletableFuture<ToolResult> execute(
                Map<String, Object> parameters, ToolExecutionContext context) {
            calls++;
            this.parameters = parameters;
            this.context = context;
            return future != null ? future : CompletableFuture.completedFuture(result);
        }

        @Override
        public boolean isMutating() {
            return mutating;
        }

        @Override
        public boolean requiresConfirmation() {
            return requiresConfirmation;
        }

        @Override
        public boolean isDestructive() {
            return destructive;
        }
    }

    private static final class StaticProfile implements AgentProfile {

        private final String id;
        private final Set<String> allowedTools;
        private final List<PermissionRule> rules;
        private final boolean readOnly;

        private StaticProfile(
                String id, Set<String> allowedTools, List<PermissionRule> rules,
                boolean readOnly) {
            this.id = id;
            this.allowedTools = allowedTools;
            this.rules = rules;
            this.readOnly = readOnly;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getName() {
            return id;
        }

        @Override
        public String getDescription() {
            return "test profile"; //$NON-NLS-1$
        }

        @Override
        public Set<String> getAllowedTools() {
            return allowedTools;
        }

        @Override
        public List<PermissionRule> getDefaultPermissions() {
            return rules;
        }

        @Override
        public String getSystemPromptAddition() {
            return null;
        }

        @Override
        public int getMaxSteps() {
            return 1;
        }

        @Override
        public long getTimeoutMs() {
            return 1000;
        }

        @Override
        public boolean isReadOnly() {
            return readOnly;
        }

        @Override
        public boolean canExecuteShell() {
            return !readOnly;
        }

    }

    private static final class AllowAllExposurePolicy implements McpToolExposurePolicy {

        @Override
        public boolean isExposed(String toolName) {
            return true;
        }

        @Override
        public boolean requiresConfirmation(String toolName, Map<String, Object> args) {
            return false;
        }

        @Override
        public boolean isDestructive(String toolName) {
            return false;
        }
    }

    private static final class NamedExposurePolicy implements McpToolExposurePolicy {

        private final Set<String> exposed;

        private NamedExposurePolicy(Set<String> exposed) {
            this.exposed = exposed;
        }

        @Override
        public boolean isExposed(String toolName) {
            return exposed.contains(toolName);
        }

        @Override
        public boolean requiresConfirmation(String toolName, Map<String, Object> args) {
            return false;
        }

        @Override
        public boolean isDestructive(String toolName) {
            return false;
        }
    }

    private static final class ConfirmingExposurePolicy implements McpToolExposurePolicy {

        private final String exposedTool;

        private ConfirmingExposurePolicy(String exposedTool) {
            this.exposedTool = exposedTool;
        }

        @Override
        public boolean isExposed(String toolName) {
            return exposedTool.equals(toolName);
        }

        @Override
        public boolean requiresConfirmation(String toolName, Map<String, Object> args) {
            return exposedTool.equals(toolName);
        }

        @Override
        public boolean isDestructive(String toolName) {
            return false;
        }
    }

    private static final class TimeoutExecutionService extends ToolExecutionService {

        private TimeoutExecutionService(ToolRegistry registry) {
            super(registry);
        }

        @Override
        public CompletableFuture<ToolResult> execute(
                ToolCall toolCall, Map<String, Object> parameters,
                AgentTraceSession traceSession, String parentEventId,
                ToolExecutionContext context) {
            return CompletableFuture.failedFuture(new TimeoutException("forced timeout")); //$NON-NLS-1$
        }
    }
}
