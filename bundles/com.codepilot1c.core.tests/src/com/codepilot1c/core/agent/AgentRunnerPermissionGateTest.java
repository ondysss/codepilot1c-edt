/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.Test;

import com.codepilot1c.core.agent.events.AgentEvent;
import com.codepilot1c.core.agent.events.ConfirmationRequiredEvent;
import com.codepilot1c.core.agent.events.IAgentEventListener;
import com.codepilot1c.core.agent.events.ToolCallEvent;
import com.codepilot1c.core.agent.events.ToolResultEvent;
import com.codepilot1c.core.agent.profiles.AgentCapability;
import com.codepilot1c.core.agent.profiles.DynamicToolCapability;
import com.codepilot1c.core.evaluation.trace.AgentTraceSession;
import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.LlmStreamChunk;
import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.mcp.McpToolAdapter;
import com.codepilot1c.core.mcp.model.McpTool;
import com.codepilot1c.core.provider.ILlmProvider;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolExecutionService;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolRegistry.ToolResolution;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolExecutionContext;
import com.codepilot1c.core.tools.surface.ToolSurfaceAugmentor;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import sun.misc.Unsafe;

public class AgentRunnerPermissionGateTest {

    @Test
    public void writeFileToMdoIsDeniedInGsdExecuteProfile() throws Exception {
        CountingTool writeFile = new CountingTool("write_file"); //$NON-NLS-1$
        AgentRunner runner = runnerWith(writeFile);
        List<AgentEvent> events = captureEvents(runner);
        AgentConfig config = AgentConfig.builder()
                .profileName("gsd-execute") //$NON-NLS-1$
                .enableTool("write_file") //$NON-NLS-1$
                .build();

        invokeExecute(runner, new ToolCall("call-1", "write_file", //$NON-NLS-1$ //$NON-NLS-2$
                "{\"path\":\"src/Configuration/Configuration.mdo\"}"), config); //$NON-NLS-1$

        assertEquals(0, writeFile.executions.get());
        ToolResult result = onlyResult(events);
        assertFalse(result.isSuccess());
        assertEquals("permission_denied", result.getStructuredString("error")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("write_file", result.getStructuredString("tool")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("gsd-execute", result.getStructuredString("profile")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("denied_by_profile_rule", //$NON-NLS-1$
                result.getStructuredString("reason_code")); //$NON-NLS-1$
        assertEquals("profile", result.getStructuredString("layer")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Прямая запись .mdo и Configuration.mdo запрещена; используй EDT mutation tools", //$NON-NLS-1$
                result.getStructuredString("rule_description")); //$NON-NLS-1$
        assertEquals("src/Configuration/Configuration.mdo", //$NON-NLS-1$
                result.getStructuredString("resource")); //$NON-NLS-1$
        assertTrue(events.stream().noneMatch(ToolCallEvent.class::isInstance));
        assertTrue(runner.getConversationHistory().get(1).getContent()
                .contains("Инструмент запрещен политикой профиля")); //$NON-NLS-1$
    }

    @Test
    public void trailingCommaCannotBypassMdoDeny() throws Exception {
        CountingTool writeFile = new CountingTool("write_file"); //$NON-NLS-1$
        AgentRunner runner = runnerWith(writeFile);
        List<AgentEvent> events = captureEvents(runner);
        AgentConfig config = AgentConfig.builder()
                .profileName("gsd-execute") //$NON-NLS-1$
                .enableTool("write_file") //$NON-NLS-1$
                .build();

        invokeExecute(runner, new ToolCall("call-1", "write_file", //$NON-NLS-1$ //$NON-NLS-2$
                "{\"path\":\"src/Cfg/Configuration.mdo\",}"), config); //$NON-NLS-1$

        assertEquals(0, writeFile.executions.get());
        ToolResult result = onlyResult(events);
        assertEquals("permission_denied", result.getStructuredString("error")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("src/Cfg/Configuration.mdo", result.getStructuredString("resource")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void trailingCommaUsesApprovedParsedArgumentsForExecution() throws Exception {
        CountingTool writeFile = new CountingTool("write_file"); //$NON-NLS-1$
        AgentRunner runner = runnerWith(writeFile);
        AtomicReference<ConfirmationRequiredEvent> confirmation = new AtomicReference<>();
        autoConfirm(runner, confirmation);
        AgentConfig config = AgentConfig.builder()
                .profileName("gsd-execute") //$NON-NLS-1$
                .enableTool("write_file") //$NON-NLS-1$
                .build();

        invokeExecute(runner, new ToolCall("call-1", "write_file", //$NON-NLS-1$ //$NON-NLS-2$
                "{\"path\":\"src/Main.bsl\",}"), config); //$NON-NLS-1$

        assertEquals(1, writeFile.executions.get());
        assertEquals("src/Main.bsl", writeFile.lastParameters.get().get("path")); //$NON-NLS-1$ //$NON-NLS-2$
        assertSame(confirmation.get().getArguments(), writeFile.lastParameters.get());
    }

    @Test
    public void editFileToMdoIsDeniedInGsdExecuteProfile() throws Exception {
        CountingTool editFile = new CountingTool("edit_file"); //$NON-NLS-1$
        AgentRunner runner = runnerWith(editFile);
        List<AgentEvent> events = captureEvents(runner);
        AgentConfig config = AgentConfig.builder()
                .profileName("gsd-execute") //$NON-NLS-1$
                .enableTool("edit_file") //$NON-NLS-1$
                .build();

        invokeExecute(runner, new ToolCall("call-1", "edit_file", //$NON-NLS-1$ //$NON-NLS-2$
                "{\"path\":\"Configuration.MDO\","
                        + "\"allow_metadata_descriptor_edit\":true}"), config); //$NON-NLS-1$

        assertEquals(0, editFile.executions.get());
        ToolResult result = onlyResult(events);
        assertEquals("denied_by_profile_rule", //$NON-NLS-1$
                result.getStructuredString("reason_code")); //$NON-NLS-1$
        assertEquals("Configuration.MDO", result.getStructuredString("resource")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void writeFileToBslIsNotDeniedInGsdExecuteProfile() throws Exception {
        CountingTool writeFile = new CountingTool("write_file"); //$NON-NLS-1$
        AgentRunner runner = runnerWith(writeFile);
        autoConfirm(runner, null);
        AgentConfig config = AgentConfig.builder()
                .profileName("gsd-execute") //$NON-NLS-1$
                .enableTool("write_file") //$NON-NLS-1$
                .build();

        invokeExecute(runner, new ToolCall("call-1", "write_file", //$NON-NLS-1$ //$NON-NLS-2$
                "{\"path\":\"src/Main.bsl\"}"), config); //$NON-NLS-1$

        assertEquals(1, writeFile.executions.get());
    }

    @Test
    public void releaseNotesAreNotDeniedInGsdShipProfile() throws Exception {
        CountingTool writeFile = new CountingTool("write_file"); //$NON-NLS-1$
        AgentRunner runner = runnerWith(writeFile);
        autoConfirm(runner, null);
        AgentConfig config = AgentConfig.builder()
                .profileName("gsd-ship") //$NON-NLS-1$
                .enableTool("write_file") //$NON-NLS-1$
                .build();

        invokeExecute(runner, new ToolCall("call-1", "write_file", //$NON-NLS-1$ //$NON-NLS-2$
                "{\"path\":\"release-notes.md\"}"), config); //$NON-NLS-1$

        assertEquals(1, writeFile.executions.get());
    }

    @Test
    public void arbitrarySourceWriteIsDeniedInGsdShipProfile() throws Exception {
        CountingTool writeFile = new CountingTool("write_file"); //$NON-NLS-1$
        AgentRunner runner = runnerWith(writeFile);
        List<AgentEvent> events = captureEvents(runner);
        autoConfirm(runner, null);
        AgentConfig config = AgentConfig.builder()
                .profileName("gsd-ship") //$NON-NLS-1$
                .enableTool("write_file") //$NON-NLS-1$
                .build();

        invokeExecute(runner, new ToolCall("call-1", "write_file", //$NON-NLS-1$ //$NON-NLS-2$
                "{\"path\":\"src/Main.java\"}"), config); //$NON-NLS-1$

        assertEquals(0, writeFile.executions.get());
        assertEquals("denied_by_profile_rule", //$NON-NLS-1$
                onlyResult(events).getStructuredString("reason_code")); //$NON-NLS-1$
    }

    @Test
    public void mutatingToolIsDeniedForEveryReadOnlyProfileEvenWhenConfigIsPermissive()
            throws Exception {
        for (String profileId : List.of(
                "plan", "explore", "gsd-discuss", "gsd-plan", "gsd-verify")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            CountingTool editFile = new CountingTool("edit_file"); //$NON-NLS-1$
            AgentRunner runner = runnerWith(editFile);
            List<AgentEvent> events = captureEvents(runner);
            AgentConfig config = AgentConfig.builder()
                    .profileName(profileId)
                    .build();

            assertTrue(config.isToolAllowed("edit_file")); //$NON-NLS-1$
            invokeExecute(runner, new ToolCall("call-1", "edit_file", //$NON-NLS-1$ //$NON-NLS-2$
                    "{\"path\":\"src/Main.bsl\"}"), config); //$NON-NLS-1$

            assertEquals(profileId, 0, editFile.executions.get());
            ToolResult result = onlyResult(events);
            assertEquals(profileId, "permission_denied", //$NON-NLS-1$
                    result.getStructuredString("error")); //$NON-NLS-1$
            assertEquals(profileId, "tool_not_in_profile", //$NON-NLS-1$
                    result.getStructuredString("reason")); //$NON-NLS-1$
            assertEquals(profileId, "tool_not_in_profile", //$NON-NLS-1$
                    result.getStructuredString("reason_code")); //$NON-NLS-1$
            assertEquals(profileId, "profile", result.getStructuredString("layer")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(profileId, "", result.getStructuredString("rule_description")); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(profileId, result.getStructuredData().has("resource")); //$NON-NLS-1$
        }
    }

    @Test
    public void readToolIsExecutedForReadOnlyProfile() throws Exception {
        CountingTool readFile = new CountingTool("read_file"); //$NON-NLS-1$
        AgentRunner runner = runnerWith(readFile);
        AgentConfig config = AgentConfig.builder()
                .profileName("plan") //$NON-NLS-1$
                .build();

        invokeExecute(runner, new ToolCall("call-1", "read_file", //$NON-NLS-1$ //$NON-NLS-2$
                "{\"path\":\"src/Main.bsl\"}"), config); //$NON-NLS-1$

        assertEquals(1, readFile.executions.get());
    }

    @Test
    public void gateAskRequiresConfirmationBeforeExecution() throws Exception {
        CountingTool writeFile = new CountingTool("write_file"); //$NON-NLS-1$
        AgentRunner runner = runnerWith(writeFile);
        List<AgentEvent> events = captureEvents(runner);

        invokeExecute(runner, new ToolCall("call-ask", "write_file", //$NON-NLS-1$ //$NON-NLS-2$
                "{\"path\":\"src/Main.bsl\"}"), AgentConfig.builder() //$NON-NLS-1$
                .profileName("gsd-execute").enableTool("write_file").build()); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(0, writeFile.executions.get());
        assertEquals("confirmation_unavailable", //$NON-NLS-1$
                onlyResult(events).getStructuredString("reason_code")); //$NON-NLS-1$
    }

    @Test
    public void toolConfirmationAndDestructiveSignalsBothRequireConfirmation() throws Exception {
        for (CountingTool tool : List.of(
                new CountingTool("read_file", true, false), //$NON-NLS-1$
                new CountingTool("read_file", false, true))) { //$NON-NLS-1$
            AgentRunner runner = runnerWith(tool);
            List<AgentEvent> events = captureEvents(runner);

            invokeExecute(runner, new ToolCall("call-policy", "read_file", "{}"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    AgentConfig.builder().profileName("plan").build()); //$NON-NLS-1$

            assertEquals(0, tool.executions.get());
            assertEquals("confirmation_unavailable_tool_policy", //$NON-NLS-1$
                    onlyResult(events).getStructuredString("reason_code")); //$NON-NLS-1$
            assertTrue(events.stream().anyMatch(event -> event instanceof ToolCallEvent call
                    && call.isRequiresConfirmation()));
        }
    }

    @Test
    public void mutatingDynamicNoRuleCannotExecuteWithoutConfirmation() throws Exception {
        String toolName = "mcp_remote_publish_release"; //$NON-NLS-1$
        McpTool advertisedTool = new McpTool("publish_release", "Publish"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject untrustedHints = new JsonObject();
        untrustedHints.addProperty("readOnlyHint", true); //$NON-NLS-1$
        advertisedTool.setAnnotations(untrustedHints);
        DynamicToolCapability remoteCapability =
                McpToolAdapter.dynamicToolCapabilityOf(advertisedTool);
        assertEquals(DynamicToolCapability.MUTATING, remoteCapability);
        CountingTool tool = new CountingTool(toolName);
        AgentRunner runner = runnerWithDynamic(tool, remoteCapability);
        List<AgentEvent> events = captureEvents(runner);

        invokeExecute(runner, new ToolCall("call-mcp", toolName, "{}"), //$NON-NLS-1$ //$NON-NLS-2$
                AgentConfig.builder().profileName("build").build()); //$NON-NLS-1$

        assertEquals(0, tool.executions.get());
        assertEquals("confirmation_unavailable_tool_policy", //$NON-NLS-1$
                onlyResult(events).getStructuredString("reason_code")); //$NON-NLS-1$

        CountingTool confirmedTool = new CountingTool(toolName);
        AgentRunner confirmedRunner = runnerWithDynamic(confirmedTool, remoteCapability);
        autoConfirm(confirmedRunner, null);
        invokeExecute(confirmedRunner, new ToolCall("call-mcp-confirmed", toolName, "{}"), //$NON-NLS-1$ //$NON-NLS-2$
                AgentConfig.builder().profileName("build").build()); //$NON-NLS-1$
        assertEquals(1, confirmedTool.executions.get());
    }

    @Test
    public void deniedResultIsDeterministic() throws Exception {
        CountingTool editFile = new CountingTool("edit_file"); //$NON-NLS-1$
        AgentRunner runner = runnerWith(editFile);
        List<AgentEvent> events = captureEvents(runner);
        AgentConfig config = AgentConfig.builder().profileName("plan").build(); //$NON-NLS-1$

        invokeExecute(runner, new ToolCall("call-1", "edit_file", "{}"), config); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        invokeExecute(runner, new ToolCall("call-2", "edit_file", "{}"), config); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        List<ToolResult> results = events.stream()
                .filter(ToolResultEvent.class::isInstance)
                .map(ToolResultEvent.class::cast)
                .map(ToolResultEvent::getResult)
                .toList();
        assertEquals(2, results.size());
        assertEquals(results.get(0).getContentForLlm(), results.get(1).getContentForLlm());
        assertEquals(results.get(0).getStructuredData(), results.get(1).getStructuredData());
    }

    @Test
    public void runnerPassesTrustedProfileAndDepthToToolExecution() throws Exception {
        ContextCapturingTool task = new ContextCapturingTool();
        AgentRunner runner = runnerWith(task);
        AgentConfig config = AgentConfig.builder()
                .profileName("plan") //$NON-NLS-1$
                .delegationDepth(2)
                .build();

        invokeExecute(runner, new ToolCall("call-1", "task", "{}"), config); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertNotNull(task.context.get());
        assertEquals("plan", task.context.get().parentProfileId()); //$NON-NLS-1$
        assertEquals(AgentCapability.READ_ONLY, task.context.get().delegationCeiling());
        assertEquals(2, task.context.get().delegationDepth());
    }

    @Test
    public void readOnlyDynamicReplacementBeforeDirectDispatchFailsStale() throws Exception {
        String name = "mcp_runner_direct_race"; //$NON-NLS-1$
        CountingTool authorized = new CountingTool(name);
        CountingTool replacement = new CountingTool(name, false, true);
        ToolRegistry registry = isolatedRegistry(Map.of());
        registry.registerDynamicTool(authorized, DynamicToolCapability.READ_ONLY);
        setField(registry, "executionService", new ReplacingExecutionService( //$NON-NLS-1$
                registry, replacement, DynamicToolCapability.MUTATING));
        AgentRunner runner = runnerWith(registry);
        List<AgentEvent> events = captureEvents(runner);

        invokeExecute(runner, new ToolCall("direct-race", name, "{}"), //$NON-NLS-1$ //$NON-NLS-2$
                AgentConfig.builder().profileName("build").build()); //$NON-NLS-1$

        assertEquals(0, authorized.executions.get());
        assertEquals(0, replacement.executions.get());
        assertEquals(ToolExecutionService.STALE_RESOLUTION_ERROR,
                onlyResult(events).getStructuredString("error")); //$NON-NLS-1$
    }

    @Test
    public void readOnlyDynamicReplacementWhileConfirmationPendingFailsStale()
            throws Exception {
        String name = "mcp_runner_confirmation_race"; //$NON-NLS-1$
        CountingTool authorized = new CountingTool(name, true, false);
        CountingTool replacement = new CountingTool(name, false, true);
        ToolRegistry registry = isolatedRegistry(Map.of());
        registry.registerDynamicTool(authorized, DynamicToolCapability.READ_ONLY);
        AgentRunner runner = runnerWith(registry);
        List<AgentEvent> events = captureEvents(runner);
        AtomicReference<ConfirmationRequiredEvent> pending = new AtomicReference<>();
        runner.addListener(new IAgentEventListener() {
            @Override
            public void onEvent(AgentEvent event) {
                if (event instanceof ConfirmationRequiredEvent confirmation) {
                    pending.set(confirmation);
                }
            }

            @Override
            public boolean handlesConfirmations() {
                return true;
            }
        });

        CompletableFuture<Void> execution = invokeExecuteFuture(runner,
                new ToolCall("confirmation-race", name, "{}"), //$NON-NLS-1$ //$NON-NLS-2$
                AgentConfig.builder().profileName("build").build()); //$NON-NLS-1$
        assertNotNull(pending.get());
        assertFalse(execution.isDone());

        registry.registerDynamicTool(replacement, DynamicToolCapability.MUTATING);
        pending.get().confirm();
        execution.join();

        assertEquals(0, authorized.executions.get());
        assertEquals(0, replacement.executions.get());
        assertEquals(ToolExecutionService.STALE_RESOLUTION_ERROR,
                onlyResult(events).getStructuredString("reason_code")); //$NON-NLS-1$
    }

    @Test
    public void dynamicCollisionCannotReplaceAuthorizedBuiltIn() throws Exception {
        CountingTool builtIn = new CountingTool("read_file"); //$NON-NLS-1$
        CountingTool collision = new CountingTool("read_file", false, true); //$NON-NLS-1$
        ToolRegistry registry = isolatedRegistry(Map.of(builtIn.getName(), builtIn));
        registry.registerDynamicTool(collision, DynamicToolCapability.MUTATING);
        AgentRunner runner = runnerWith(registry);

        invokeExecute(runner, new ToolCall("builtin-collision", "read_file", "{}"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                AgentConfig.builder().profileName("plan").build()); //$NON-NLS-1$

        assertEquals(1, builtIn.executions.get());
        assertEquals(0, collision.executions.get());
    }

    private static AgentRunner runnerWith(ITool tool) throws Exception {
        return runnerWith(isolatedRegistry(Map.of(tool.getName(), tool)));
    }

    private static AgentRunner runnerWith(ToolRegistry registry) throws Exception {
        AgentRunner runner = new AgentRunner(new NoopProvider(), registry, "system"); //$NON-NLS-1$
        Field field = AgentRunner.class.getDeclaredField("conversationHistory"); //$NON-NLS-1$
        field.setAccessible(true);
        field.set(runner, new ArrayList<>(List.of(LlmMessage.user("test")))); //$NON-NLS-1$
        return runner;
    }

    private static AgentRunner runnerWithDynamic(
            ITool tool, DynamicToolCapability capability) throws Exception {
        ToolRegistry registry = isolatedRegistry(Map.of());
        registry.registerDynamicTool(tool, capability);
        AgentRunner runner = new AgentRunner(new NoopProvider(), registry, "system"); //$NON-NLS-1$
        Field field = AgentRunner.class.getDeclaredField("conversationHistory"); //$NON-NLS-1$
        field.setAccessible(true);
        field.set(runner, new ArrayList<>(List.of(LlmMessage.user("test")))); //$NON-NLS-1$
        return runner;
    }

    private static List<AgentEvent> captureEvents(AgentRunner runner) {
        List<AgentEvent> events = new ArrayList<>();
        runner.addListener(events::add);
        return events;
    }

    private static void autoConfirm(
            AgentRunner runner, AtomicReference<ConfirmationRequiredEvent> captured) {
        runner.addListener(new IAgentEventListener() {
            @Override
            public void onEvent(AgentEvent event) {
                if (event instanceof ConfirmationRequiredEvent confirmation) {
                    if (captured != null) {
                        captured.set(confirmation);
                    }
                    confirmation.confirm();
                }
            }

            @Override
            public boolean handlesConfirmations() {
                return true;
            }
        });
    }

    private static ToolResult onlyResult(List<AgentEvent> events) {
        ToolResult result = events.stream()
                .filter(ToolResultEvent.class::isInstance)
                .map(ToolResultEvent.class::cast)
                .map(ToolResultEvent::getResult)
                .findFirst()
                .orElse(null);
        assertNotNull(result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void invokeExecute(AgentRunner runner, ToolCall call, AgentConfig config)
            throws Exception {
        invokeExecuteFuture(runner, call, config).join();
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<Void> invokeExecuteFuture(
            AgentRunner runner, ToolCall call, AgentConfig config) throws Exception {
        Method method = AgentRunner.class.getDeclaredMethod(
                "executeSingleToolCall", ToolCall.class, AgentConfig.class); //$NON-NLS-1$
        method.setAccessible(true);
        return (CompletableFuture<Void>) method.invoke(runner, call, config);
    }

    private static ToolRegistry isolatedRegistry(Map<String, ITool> tools) throws Exception {
        ToolRegistry registry = (ToolRegistry) unsafe().allocateInstance(ToolRegistry.class);
        setField(registry, "tools", new HashMap<>(tools)); //$NON-NLS-1$
        setField(registry, "dynamicTools", new ConcurrentHashMap<String, ITool>()); //$NON-NLS-1$
        setField(registry, "dynamicToolCapabilities", //$NON-NLS-1$
                new ConcurrentHashMap<String, DynamicToolCapability>());
        setField(registry, "gson", new Gson()); //$NON-NLS-1$
        setField(registry, "augmentor", ToolSurfaceAugmentor.passthrough()); //$NON-NLS-1$
        return registry;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = ToolRegistry.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); //$NON-NLS-1$
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static final class CountingTool implements ITool {
        private final String name;
        private final boolean confirmation;
        private final boolean destructive;
        private final AtomicInteger executions = new AtomicInteger();
        private final AtomicReference<Map<String, Object>> lastParameters = new AtomicReference<>();

        private CountingTool(String name) {
            this(name, false, false);
        }

        private CountingTool(String name, boolean confirmation, boolean destructive) {
            this.name = name;
            this.confirmation = confirmation;
            this.destructive = destructive;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "Test tool " + name; //$NON-NLS-1$
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            lastParameters.set(parameters);
            executions.incrementAndGet();
            return CompletableFuture.completedFuture(ToolResult.success("ok")); //$NON-NLS-1$
        }

        @Override
        public boolean requiresConfirmation() {
            return confirmation;
        }

        @Override
        public boolean isDestructive() {
            return destructive;
        }
    }

    private static final class ReplacingExecutionService extends ToolExecutionService {
        private final ToolRegistry registry;
        private final ITool replacement;
        private final DynamicToolCapability capability;

        private ReplacingExecutionService(
                ToolRegistry registry, ITool replacement,
                DynamicToolCapability capability) {
            super(registry);
            this.registry = registry;
            this.replacement = replacement;
            this.capability = capability;
        }

        @Override
        public Optional<CompletableFuture<ToolResult>> executeIfCurrent(
                ToolCall toolCall, Map<String, Object> parameters,
                AgentTraceSession traceSession, String parentEventId,
                ToolExecutionContext context, ToolResolution resolution) {
            registry.registerDynamicTool(replacement, capability);
            return super.executeIfCurrent(toolCall, parameters, traceSession,
                    parentEventId, context, resolution);
        }
    }

    private static final class ContextCapturingTool implements ITool {
        private final AtomicReference<ToolExecutionContext> context = new AtomicReference<>();

        @Override
        public String getName() {
            return "task"; //$NON-NLS-1$
        }

        @Override
        public String getDescription() {
            return "Test task"; //$NON-NLS-1$
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            return CompletableFuture.completedFuture(ToolResult.success("legacy")); //$NON-NLS-1$
        }

        @Override
        public CompletableFuture<ToolResult> execute(
                Map<String, Object> parameters, ToolExecutionContext context) {
            this.context.set(context);
            return CompletableFuture.completedFuture(ToolResult.success("ok")); //$NON-NLS-1$
        }
    }

    private static final class NoopProvider implements ILlmProvider {
        @Override
        public String getId() {
            return "noop"; //$NON-NLS-1$
        }

        @Override
        public String getDisplayName() {
            return "Noop"; //$NON-NLS-1$
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return false;
        }

        @Override
        public CompletableFuture<LlmResponse> complete(LlmRequest request) {
            return CompletableFuture.completedFuture(LlmResponse.of("ok")); //$NON-NLS-1$
        }

        @Override
        public void streamComplete(LlmRequest request, Consumer<LlmStreamChunk> consumer) {
            consumer.accept(LlmStreamChunk.complete(LlmResponse.FINISH_REASON_STOP));
        }

        @Override
        public void cancel() {
        }

        @Override
        public void dispose() {
        }
    }
}
