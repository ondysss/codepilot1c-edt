/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.Test;

import com.codepilot1c.core.agent.profiles.AgentCapability;
import com.codepilot1c.core.agent.profiles.AgentProfile;
import com.codepilot1c.core.agent.profiles.BuildAgentProfile;
import com.codepilot1c.core.agent.profiles.GsdExecuteProfile;
import com.codepilot1c.core.agent.profiles.ProfileCapabilities;
import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.permissions.PermissionRule;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolArgumentParser;
import com.codepilot1c.core.tools.ToolExecutionContext;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.meta.ToolDescriptorRegistry;

public class ChatViewPermissionGateTest {

    private static final Function<String, Map<String, Object>> EMPTY_PARSER = ignored -> Map.of();

    @Test
    public void deniedToolIsNotExecutedInChatViewLoop() {
        CountingTool tool = new CountingTool("write_file"); //$NON-NLS-1$
        ChatToolGate gate = gate(new GsdExecuteProfile(), List.of(),
                new ToolArgumentParser()::parseArguments, Set.of(), true, false);
        ToolCall call = call("write_file", //$NON-NLS-1$
                "{\"path\":\"src/Configuration/Configuration.mdo\"}"); //$NON-NLS-1$

        ChatToolGate.Decision decision = gate.decide(call, tool);
        executeOnlyWhenApproved(decision, tool);

        assertEquals(ChatToolGate.Action.DENY, decision.action());
        assertEquals(0, tool.executions.get());
        assertPermission(decision.denial(), "write_file", "gsd-execute", //$NON-NLS-1$ //$NON-NLS-2$
                "denied_by_profile_rule", "profile", //$NON-NLS-1$ //$NON-NLS-2$
                "src/Configuration/Configuration.mdo"); //$NON-NLS-1$
    }

    @Test
    public void chatViewPassesScopedExecutionContext() {
        String name = "w6_context_capture"; //$NON-NLS-1$
        ContextCapturingTool tool = new ContextCapturingTool(name);
        ToolRegistry registry = ToolRegistry.getInstance();
        registry.registerDynamicTool(tool);
        AgentProfile profile = profile("context-profile", Set.of(name), List.of(), false); //$NON-NLS-1$
        try {
            ChatToolGate gate = gate(profile, List.of(), EMPTY_PARSER,
                    registry.getDynamicToolNames(), true, false);
            ToolCall call = call(name, "{}"); //$NON-NLS-1$
            ChatToolGate.Decision decision = gate.decide(call, tool);

            assertTrue(decision.context().isScoped());
            assertEquals(profile.getId(), decision.context().parentProfileId());
            assertEquals(ProfileCapabilities.delegationCeiling(profile),
                    decision.context().delegationCeiling());
            assertEquals(0, decision.context().delegationDepth());

            registry.execute(call, decision.arguments(), null, null, decision.context()).join();
            assertSame(decision.context(), tool.context.get());
        } finally {
            registry.unregisterDynamicTool(name);
        }
    }

    @Test
    public void chatViewGateAndExecutionShareOneParsedArgumentMap() {
        String name = "w6_one_parsed_map"; //$NON-NLS-1$
        CountingTool tool = new CountingTool(name);
        ToolRegistry registry = ToolRegistry.getInstance();
        registry.registerDynamicTool(tool);
        AtomicInteger parses = new AtomicInteger();
        Map<String, Object> parsed = new LinkedHashMap<>();
        parsed.put("path", "src/Main.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        AgentProfile profile = profile("one-map", Set.of(name), List.of(), false); //$NON-NLS-1$
        try {
            ChatToolGate gate = gate(profile, List.of(), raw -> {
                parses.incrementAndGet();
                return parsed;
            }, registry.getDynamicToolNames(), true, false);
            ToolCall call = call(name, "{\"path\":\"src/Main.bsl\"}"); //$NON-NLS-1$
            ChatToolGate.Decision decision = gate.decide(call, tool);

            registry.execute(call, decision.arguments(), null, null, decision.context()).join();

            assertEquals(1, parses.get());
            assertSame(parsed, decision.arguments());
            assertSame(decision.arguments(), tool.lastParameters.get());
        } finally {
            registry.unregisterDynamicTool(name);
        }
    }

    @Test
    public void toolListIsBuiltFromSelectedProfile() {
        ToolRegistry registry = ToolRegistry.getInstance();
        BuildAgentProfile profile = new BuildAgentProfile();
        ChatToolGate gate = gate(profile, List.of(), EMPTY_PARSER,
                registry.getDynamicToolNames(), true, false);

        Set<String> visible = gate.visibleToolDefinitions(registry).stream()
                .map(definition -> definition.getName())
                .collect(java.util.stream.Collectors.toSet());
        Set<String> registeredBuiltins = registry.getAllTools().stream()
                .map(ITool::getName)
                .filter(name -> !registry.getDynamicToolNames().contains(name))
                .collect(java.util.stream.Collectors.toSet());
        Set<String> expected = profile.getAllowedTools().stream()
                .filter(registeredBuiltins::contains)
                .collect(java.util.stream.Collectors.toSet());

        assertTrue(visible.containsAll(expected));
        assertTrue(visible.stream()
                .filter(registeredBuiltins::contains)
                .allMatch(profile.getAllowedTools()::contains));
    }

    @Test
    public void denialPayloadMatchesSharedPermissionContract() {
        AgentProfile contractProfile = profile(
                "contract", Set.of("contract_tool"), List.of(), false); //$NON-NLS-1$ //$NON-NLS-2$
        CountingTool contractTool = new CountingTool("contract_tool"); //$NON-NLS-1$

        ChatToolGate outsideGate = gate(contractProfile, List.of(), EMPTY_PARSER,
                Set.of(), true, false);
        ToolResult outside = outsideGate.decide(call("outside", "{}"), //$NON-NLS-1$ //$NON-NLS-2$
                new CountingTool("outside")).denial(); //$NON-NLS-1$
        assertPayloadContract(outside, "outside", "contract", null, //$NON-NLS-1$ //$NON-NLS-2$
                "tool_not_in_profile", "profile", null); //$NON-NLS-1$ //$NON-NLS-2$

        PermissionRule globalDeny = PermissionRule.deny("contract_tool") //$NON-NLS-1$
                .withDescription("global deny") //$NON-NLS-1$
                .forAllResources();
        ToolResult denied = gate(contractProfile, List.of(globalDeny),
                new ToolArgumentParser()::parseArguments, Set.of(), true, false)
                .decide(call("contract_tool", "{\"path\":\"src/Main.bsl\"}"), contractTool) //$NON-NLS-1$ //$NON-NLS-2$
                .denial();
        assertPayloadContract(denied, "contract_tool", "contract", "src/Main.bsl", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "denied_by_global_rule", "global", "global deny"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        PermissionRule globalAsk = PermissionRule.ask("contract_tool") //$NON-NLS-1$
                .withDescription("global ask") //$NON-NLS-1$
                .forAllResources();
        ToolResult askUnavailable = gate(contractProfile, List.of(globalAsk),
                new ToolArgumentParser()::parseArguments, Set.of(), false, false)
                .decide(call("contract_tool", "{\"path\":\"src/Main.bsl\"}"), contractTool) //$NON-NLS-1$ //$NON-NLS-2$
                .denial();
        assertPayloadContract(askUnavailable, "contract_tool", "contract", "src/Main.bsl", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "confirmation_unavailable", "global", "global ask"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        CountingTool confirmationTool = new CountingTool("contract_tool", true, false); //$NON-NLS-1$
        ToolResult policyUnavailable = gate(contractProfile, List.of(), EMPTY_PARSER,
                Set.of(), false, false)
                .decide(call("contract_tool", "{}"), confirmationTool).denial(); //$NON-NLS-1$ //$NON-NLS-2$
        assertPayloadContract(policyUnavailable, "contract_tool", "contract", null, //$NON-NLS-1$ //$NON-NLS-2$
                "confirmation_unavailable_tool_policy", "tool", null); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void askDecisionRequiresConfirmationEvenWhenToolDoesNotRequireIt() {
        CountingTool tool = new CountingTool("edit_file"); //$NON-NLS-1$
        ChatToolGate.Decision decision = gate(new GsdExecuteProfile(), List.of(),
                new ToolArgumentParser()::parseArguments, Set.of(), true, false)
                .decide(call("edit_file", "{\"path\":\"src/Main.bsl\"}"), tool); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(ChatToolGate.Action.CONFIRM, decision.action());
        assertEquals("profile", decision.layer()); //$NON-NLS-1$
    }

    @Test
    public void toolPolicyConfirmationIsPreservedWhenGateIsSilent() {
        AgentProfile profile = profile("policy", Set.of("confirm", "destructive"), List.of(), false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        ChatToolGate gate = gate(profile, List.of(), EMPTY_PARSER, Set.of(), true, false);

        assertEquals(ChatToolGate.Action.CONFIRM,
                gate.decide(call("confirm", "{}"), //$NON-NLS-1$ //$NON-NLS-2$
                        new CountingTool("confirm", true, false)).action()); //$NON-NLS-1$
        assertEquals(ChatToolGate.Action.CONFIRM,
                gate.decide(call("destructive", "{}"), //$NON-NLS-1$ //$NON-NLS-2$
                        new CountingTool("destructive", false, true)).action()); //$NON-NLS-1$
    }

    @Test
    public void askWithoutConfirmationSinkFailsClosedWithDeterministicPayload() {
        CountingTool tool = new CountingTool("edit_file"); //$NON-NLS-1$
        ChatToolGate.Decision decision = gate(new GsdExecuteProfile(), List.of(),
                new ToolArgumentParser()::parseArguments, Set.of(), false, false)
                .decide(call("edit_file", "{\"path\":\"src/Main.bsl\"}"), tool); //$NON-NLS-1$ //$NON-NLS-2$
        executeOnlyWhenApproved(decision, tool);

        assertEquals(ChatToolGate.Action.DENY, decision.action());
        assertEquals(0, tool.executions.get());
        assertPermission(decision.denial(), "edit_file", "gsd-execute", //$NON-NLS-1$ //$NON-NLS-2$
                "confirmation_unavailable", "profile", "src/Main.bsl"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void toolPolicyConfirmationWithoutSinkUsesToolPolicyReasonCode() {
        CountingTool tool = new CountingTool("confirm", true, false); //$NON-NLS-1$
        AgentProfile profile = profile("policy", Set.of("confirm"), List.of(), false); //$NON-NLS-1$ //$NON-NLS-2$
        ChatToolGate.Decision decision = gate(profile, List.of(), EMPTY_PARSER,
                Set.of(), false, false).decide(call("confirm", "{}"), tool); //$NON-NLS-1$ //$NON-NLS-2$
        executeOnlyWhenApproved(decision, tool);

        assertEquals(ChatToolGate.Action.DENY, decision.action());
        assertEquals(0, tool.executions.get());
        assertEquals("confirmation_unavailable_tool_policy", //$NON-NLS-1$
                decision.denial().getStructuredString("reason_code")); //$NON-NLS-1$
        assertEquals("tool", decision.denial().getStructuredString("layer")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(decision.denial().getStructuredData().has("resource")); //$NON-NLS-1$
    }

    @Test
    public void confirmDecisionCarriesPrecomputedUnavailablePayload() {
        CountingTool askTool = new CountingTool("edit_file"); //$NON-NLS-1$
        ToolCall askCall = call("edit_file", "{\"path\":\"src/Main.bsl\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        ChatToolGate.Decision confirmAsk = gate(new GsdExecuteProfile(), List.of(),
                new ToolArgumentParser()::parseArguments, Set.of(), true, false)
                .decide(askCall, askTool);
        ChatToolGate.Decision deniedAsk = gate(new GsdExecuteProfile(), List.of(),
                new ToolArgumentParser()::parseArguments, Set.of(), false, false)
                .decide(askCall, askTool);

        assertNotNull(confirmAsk.confirmationUnavailableDenial());
        assertEquivalent(confirmAsk.confirmationUnavailableDenial(), deniedAsk.denial());

        CountingTool policyTool = new CountingTool("confirm", true, false); //$NON-NLS-1$
        AgentProfile policy = profile("policy", Set.of("confirm"), List.of(), false); //$NON-NLS-1$ //$NON-NLS-2$
        ToolCall policyCall = call("confirm", "{}"); //$NON-NLS-1$ //$NON-NLS-2$
        ChatToolGate.Decision confirmPolicy = gate(policy, List.of(), EMPTY_PARSER,
                Set.of(), true, false).decide(policyCall, policyTool);
        ChatToolGate.Decision deniedPolicy = gate(policy, List.of(), EMPTY_PARSER,
                Set.of(), false, false).decide(policyCall, policyTool);

        assertEquivalent(confirmPolicy.confirmationUnavailableDenial(), deniedPolicy.denial());
    }

    @Test
    public void vanishedDynamicToolStillYieldsDeterministicDenial() {
        String name = "w6_vanished_confirmation_tool"; //$NON-NLS-1$
        CountingTool tool = new CountingTool(name, true, false);
        ToolRegistry registry = ToolRegistry.getInstance();
        registry.registerDynamicTool(tool);
        AgentProfile profile = profile("vanished", Set.of(name), List.of(), false); //$NON-NLS-1$
        try {
            ChatToolGate gate = gate(profile, List.of(), EMPTY_PARSER,
                    registry.getDynamicToolNames(), true, false);
            ToolCall call = call(name, "{}"); //$NON-NLS-1$
            ITool resolved = registry.getTool(name);
            ChatToolGate.Decision decision = gate.decide(call, resolved);

            assertEquals(ChatToolGate.Action.CONFIRM, decision.action());
            registry.unregisterDynamicTool(name);
            assertNull(registry.getTool(name));
            assertPayloadContract(decision.confirmationUnavailableDenial(),
                    name, profile.getId(), null,
                    "confirmation_unavailable_tool_policy", "tool", null); //$NON-NLS-1$ //$NON-NLS-2$
        } finally {
            registry.unregisterDynamicTool(name);
        }
    }

    @Test
    public void chatViewConfirmationRunnableAlwaysCompletesFuture() throws IOException {
        String source = Files.readString(repositoryRoot().resolve(
                "bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/views/ChatView.java"), //$NON-NLS-1$
                StandardCharsets.UTF_8);
        int start = source.indexOf("private CompletableFuture<String> processToolCalls("); //$NON-NLS-1$
        int end = source.indexOf(
                "private synchronized ChatToolGate activeToolGate()", start); //$NON-NLS-1$
        assertTrue("processToolCalls region must exist", start >= 0 && end > start); //$NON-NLS-1$
        String region = source.substring(start, end);

        assertTrue(region.contains("resolvedTools.get(")); //$NON-NLS-1$
        assertEquals(1, occurrences(region, "registry.getTool(")); //$NON-NLS-1$
        int nullGuard = region.indexOf("if (tool == null)"); //$NON-NLS-1$
        int descriptionRead = region.indexOf("tool.getDescription()"); //$NON-NLS-1$
        assertTrue("null guard must precede tool dereference", //$NON-NLS-1$
                nullGuard >= 0 && descriptionRead > nullGuard);
        int asyncExec = region.indexOf("display.asyncExec(() ->", descriptionRead - 1000); //$NON-NLS-1$
        int throwableGuard = region.indexOf("catch (Throwable", asyncExec); //$NON-NLS-1$
        int completion = region.indexOf("confirmedFuture.complete(", throwableGuard); //$NON-NLS-1$
        assertTrue("confirmation runnable must catch Throwable and complete its future", //$NON-NLS-1$
                asyncExec >= 0 && throwableGuard > asyncExec && completion > throwableGuard);
    }

    @Test
    public void skipConfirmationsPreferenceAutoApprovesAskDecision() {
        ChatToolGate.Decision decision = gate(new GsdExecuteProfile(), List.of(),
                new ToolArgumentParser()::parseArguments, Set.of(), true, true)
                .decide(call("edit_file", "{\"path\":\"src/Main.bsl\"}"), //$NON-NLS-1$ //$NON-NLS-2$
                        new CountingTool("edit_file")); //$NON-NLS-1$

        assertEquals(ChatToolGate.Action.EXECUTE, decision.action());
        assertEquals("confirmation_skipped_by_preference", decision.reasonCode()); //$NON-NLS-1$
    }

    @Test
    public void toolOutsideProfileAllowlistIsDeniedWithoutResourceKey() {
        CountingTool tool = new CountingTool("outside"); //$NON-NLS-1$
        AgentProfile profile = profile("limited", Set.of("inside"), List.of(), true); //$NON-NLS-1$ //$NON-NLS-2$
        ChatToolGate.Decision decision = gate(profile, List.of(), EMPTY_PARSER,
                Set.of(), true, false).decide(call("outside", "{}"), tool); //$NON-NLS-1$ //$NON-NLS-2$
        executeOnlyWhenApproved(decision, tool);

        assertEquals(ChatToolGate.Action.DENY, decision.action());
        assertEquals(0, tool.executions.get());
        assertEquals("tool_not_in_profile", decision.denial().getStructuredString("reason_code")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("profile", decision.denial().getStructuredString("layer")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(decision.denial().getStructuredData().has("resource")); //$NON-NLS-1$
    }

    @Test
    public void dynamicMutatorOutsideProfileIsHiddenAndDenied() {
        String name = "w6_dynamic_surface"; //$NON-NLS-1$
        CountingTool tool = new CountingTool(name, false, true);
        ToolRegistry registry = ToolRegistry.getInstance();
        registry.registerDynamicTool(tool);
        AgentProfile profile = profile("limited", Set.of("read_file"), List.of(), false); //$NON-NLS-1$ //$NON-NLS-2$
        try {
            ChatToolGate gate = gate(profile, List.of(), EMPTY_PARSER,
                    registry.getDynamicToolNames(), true, false);

            assertFalse(gate.visibleToolDefinitions(registry).stream()
                    .anyMatch(definition -> name.equals(definition.getName())));
            ChatToolGate.Decision decision = gate.decide(call(name, "{}"), tool); //$NON-NLS-1$
            assertEquals(ChatToolGate.Action.DENY, decision.action());
            assertEquals("tool_not_in_profile", decision.reasonCode()); //$NON-NLS-1$
        } finally {
            registry.unregisterDynamicTool(name);
        }
    }

    @Test
    public void builtInToolWinsOverDynamicToolWithSameAllowedName() {
        String name = "read_file"; //$NON-NLS-1$
        CountingTool dynamic = new CountingTool(name, false, true);
        ToolRegistry registry = ToolRegistry.getInstance();
        ITool builtIn = registry.getTool(name);
        registry.registerDynamicTool(dynamic);
        AgentProfile profile = profile("builtin-precedence", Set.of(name), List.of(), true); //$NON-NLS-1$
        try {
            ChatToolGate gate = gate(profile, List.of(), EMPTY_PARSER,
                    registry.getDynamicToolNames(), true, false);

            assertSame(builtIn, registry.getTool(name));
            assertTrue(gate.visibleToolDefinitions(registry).stream()
                    .anyMatch(definition -> name.equals(definition.getName())));
            assertEquals(ChatToolGate.Action.EXECUTE,
                    gate.decide(call(name, "{}"), registry.getTool(name)).action()); //$NON-NLS-1$
            assertEquals(0, dynamic.executions.get());
        } finally {
            registry.unregisterDynamicTool(name);
            ToolDescriptorRegistry.getInstance().registerTool(builtIn);
        }
    }

    @Test
    public void mdoDenyWinsOverAskInChatGate() {
        CountingTool tool = new CountingTool("edit_file"); //$NON-NLS-1$
        PermissionRule globalAsk = PermissionRule.ask("edit_file").forAllResources(); //$NON-NLS-1$
        ChatToolGate.Decision decision = gate(new GsdExecuteProfile(), List.of(globalAsk),
                new ToolArgumentParser()::parseArguments, Set.of(), true, false)
                .decide(call("edit_file", "{\"path\":\"src/Configuration.mdo\"}"), tool); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(ChatToolGate.Action.DENY, decision.action());
        assertEquals("denied_by_profile_rule", decision.reasonCode()); //$NON-NLS-1$
    }

    @Test
    public void unavailableGlobalRulesAreContainedAndProfileStillApplies() {
        CountingTool tool = new CountingTool("write_file"); //$NON-NLS-1$
        ChatToolGate gate = new ChatToolGate(
                new GsdExecuteProfile(),
                () -> { throw new AssertionError("unavailable"); }, //$NON-NLS-1$
                new ToolArgumentParser()::parseArguments,
                Set::of,
                () -> true,
                () -> false);

        ChatToolGate.Decision decision = gate.decide(call("write_file", //$NON-NLS-1$
                "{\"path\":\"src/Configuration.mdo\"}"), tool); //$NON-NLS-1$

        assertEquals(ChatToolGate.Action.DENY, decision.action());
        assertEquals("denied_by_profile_rule", decision.reasonCode()); //$NON-NLS-1$
    }

    @Test
    public void malformedArgumentsAreContainedAndGateStillDecides() {
        ChatToolGate gate = new ChatToolGate(
                new GsdExecuteProfile(),
                List::of,
                ignored -> { throw new StackOverflowError("malformed"); }, //$NON-NLS-1$
                Set::of,
                () -> true,
                () -> false);

        ChatToolGate.Decision decision = gate.decide(
                call("edit_file", "not-json"), new CountingTool("edit_file")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(decision.arguments().isEmpty());
        assertEquals(ChatToolGate.Action.CONFIRM, decision.action());
        assertEquals("profile", decision.layer()); //$NON-NLS-1$
    }

    @Test
    public void repairedArgumentsAreApprovedAndExecutedFromOneMap() {
        String name = "w6_repaired_arguments"; //$NON-NLS-1$
        CountingTool tool = new CountingTool(name, true, false);
        ToolRegistry registry = ToolRegistry.getInstance();
        registry.registerDynamicTool(tool);
        AgentProfile profile = profile("repair", Set.of(name), List.of(), false); //$NON-NLS-1$
        try {
            ChatToolGate gate = gate(profile, List.of(),
                    registry.getExecutionService()::parseArguments,
                    registry.getDynamicToolNames(), true, false);
            ToolCall call = call(name, "{\"path\":\"src/Main.bsl\",}"); //$NON-NLS-1$
            ChatToolGate.Decision decision = gate.decide(call, tool);

            assertEquals(ChatToolGate.Action.CONFIRM, decision.action());
            registry.execute(call, decision.arguments(), null, null, decision.context()).join();

            assertEquals("src/Main.bsl", decision.arguments().get("path")); //$NON-NLS-1$ //$NON-NLS-2$
            assertSame(decision.arguments(), tool.lastParameters.get());
        } finally {
            registry.unregisterDynamicTool(name);
        }
    }

    @Test
    public void deniedPreviewCallIsNotInterceptedForDiffReview() {
        ChatToolGate gate = gate(new GsdExecuteProfile(), List.of(),
                new ToolArgumentParser()::parseArguments, Set.of(), true, false);
        ToolCall call = call("edit_file", "{\"path\":\"src/Configuration.mdo\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        ChatToolGate.Decision decision = gate.decide(call, new CountingTool("edit_file")); //$NON-NLS-1$

        assertEquals(ChatToolGate.Action.DENY, decision.action());
        assertFalse(gate.interceptForPreview(call, decision, true));
    }

    @Test
    public void approvedPreviewCallIsInterceptedWithoutSecondConfirmation() {
        ChatToolGate gate = gate(new GsdExecuteProfile(), List.of(),
                new ToolArgumentParser()::parseArguments, Set.of(), true, false);
        ToolCall call = call("edit_file", "{\"path\":\"src/Main.bsl\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        ChatToolGate.Decision decision = gate.decide(call, new CountingTool("edit_file")); //$NON-NLS-1$

        assertEquals(ChatToolGate.Action.CONFIRM, decision.action());
        assertTrue(gate.interceptForPreview(call, decision, true));
    }

    @Test
    public void selectedProfileDefaultsToBuildAndUnknownIdFallsBack() {
        assertEquals(BuildAgentProfile.ID, ChatToolGate.selectProfile("").getId()); //$NON-NLS-1$
        assertEquals(BuildAgentProfile.ID, ChatToolGate.selectProfile(null).getId());
        assertEquals(BuildAgentProfile.ID,
                ChatToolGate.selectProfile("no-such-profile").getId()); //$NON-NLS-1$
    }

    @Test
    public void unknownToolIsLeftToExecutionServiceContract() {
        AgentProfile profile = profile("unknown", Set.of("known"), List.of(), false); //$NON-NLS-1$ //$NON-NLS-2$
        ChatToolGate.Decision decision = gate(profile, List.of(), EMPTY_PARSER,
                Set.of(), true, false).decide(call("unknown", "{}"), null); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(ChatToolGate.Action.EXECUTE, decision.action());
        assertNull(decision.denial());
        assertTrue(decision.context().isScoped());
    }

    private static ChatToolGate gate(
            AgentProfile profile,
            List<PermissionRule> globalRules,
            Function<String, Map<String, Object>> parser,
            Set<String> dynamicTools,
            boolean confirmationSinkAvailable,
            boolean skipConfirmations) {
        return new ChatToolGate(
                profile,
                () -> globalRules,
                parser,
                () -> dynamicTools,
                () -> confirmationSinkAvailable,
                () -> skipConfirmations);
    }

    private static ToolCall call(String name, String arguments) {
        return new ToolCall("call-" + name, name, arguments); //$NON-NLS-1$
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int index = 0; (index = text.indexOf(needle, index)) >= 0; index += needle.length()) {
            count++;
        }
        return count;
    }

    private static Path repositoryRoot() {
        String configured = System.getProperty("maven.multiModuleProjectDirectory"); //$NON-NLS-1$
        Path current = configured != null && !configured.isBlank()
                ? Path.of(configured).toAbsolutePath().normalize()
                : Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(); //$NON-NLS-1$
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml")) //$NON-NLS-1$
                    && Files.isRegularFile(current.resolve(
                            "bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/views/ChatView.java"))) { //$NON-NLS-1$
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root"); //$NON-NLS-1$
    }

    private static void executeOnlyWhenApproved(
            ChatToolGate.Decision decision, CountingTool tool) {
        if (decision.action() == ChatToolGate.Action.EXECUTE) {
            tool.execute(decision.arguments(), decision.context()).join();
        }
    }

    private static void assertPermission(
            ToolResult result,
            String tool,
            String profile,
            String reasonCode,
            String layer,
            String resource) {
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals("permission_denied", result.getStructuredString("error")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(tool, result.getStructuredString("tool")); //$NON-NLS-1$
        assertEquals(profile, result.getStructuredString("profile")); //$NON-NLS-1$
        assertEquals(reasonCode, result.getStructuredString("reason")); //$NON-NLS-1$
        assertEquals(reasonCode, result.getStructuredString("reason_code")); //$NON-NLS-1$
        assertEquals(layer, result.getStructuredString("layer")); //$NON-NLS-1$
        assertEquals(resource, result.getStructuredString("resource")); //$NON-NLS-1$
    }

    private static void assertPayloadContract(
            ToolResult result,
            String tool,
            String profile,
            String resource,
            String reasonCode,
            String layer,
            String description) {
        assertPermission(result, tool, profile, reasonCode, layer, resource);
        Set<String> expectedKeys = resource != null
                ? Set.of("error", "tool", "profile", "reason", "reason_code", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                        "layer", "rule_description", "resource") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                : Set.of("error", "tool", "profile", "reason", "reason_code", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                        "layer", "rule_description"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(expectedKeys, result.getStructuredData().keySet());
        assertEquals(description != null ? description : "", //$NON-NLS-1$
                result.getStructuredString("rule_description")); //$NON-NLS-1$

        StringBuilder expected = new StringBuilder()
                .append("Инструмент запрещен политикой профиля: ").append(tool) //$NON-NLS-1$
                .append(" (profile=").append(profile); //$NON-NLS-1$
        if (resource != null) {
            expected.append(", resource=").append(resource); //$NON-NLS-1$
        }
        expected.append(", reason_code=").append(reasonCode) //$NON-NLS-1$
                .append(", layer=").append(layer); //$NON-NLS-1$
        if (description != null && !description.isBlank()) {
            expected.append(", rule_description=").append(description); //$NON-NLS-1$
        }
        expected.append(')');
        assertEquals(expected.toString(), result.getErrorMessage());
    }

    private static void assertEquivalent(ToolResult expected, ToolResult actual) {
        assertNotNull(expected);
        assertNotNull(actual);
        assertEquals(expected.getErrorMessage(), actual.getErrorMessage());
        assertEquals(expected.getStructuredData(), actual.getStructuredData());
    }

    private static AgentProfile profile(
            String id,
            Set<String> allowedTools,
            List<PermissionRule> permissions,
            boolean readOnly) {
        return new TestProfile(id, allowedTools, permissions, readOnly);
    }

    private static final class TestProfile implements AgentProfile {
        private final String id;
        private final Set<String> allowedTools;
        private final List<PermissionRule> permissions;
        private final boolean readOnly;

        private TestProfile(
                String id,
                Set<String> allowedTools,
                List<PermissionRule> permissions,
                boolean readOnly) {
            this.id = id;
            this.allowedTools = allowedTools;
            this.permissions = permissions;
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
            return id;
        }

        @Override
        public Set<String> getAllowedTools() {
            return allowedTools;
        }

        @Override
        public List<PermissionRule> getDefaultPermissions() {
            return permissions;
        }

        @Override
        public String getSystemPromptAddition() {
            return null;
        }

        @Override
        public int getMaxSteps() {
            return 10;
        }

        @Override
        public long getTimeoutMs() {
            return 1_000;
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

    private static class CountingTool implements ITool {
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

    private static final class ContextCapturingTool extends CountingTool {
        private final AtomicReference<ToolExecutionContext> context = new AtomicReference<>();

        private ContextCapturingTool(String name) {
            super(name);
        }

        @Override
        public CompletableFuture<ToolResult> execute(
                Map<String, Object> parameters, ToolExecutionContext context) {
            this.context.set(context);
            return super.execute(parameters);
        }
    }
}
