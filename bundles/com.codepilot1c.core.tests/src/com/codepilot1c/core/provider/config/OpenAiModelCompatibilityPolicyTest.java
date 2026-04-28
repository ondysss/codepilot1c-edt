package com.codepilot1c.core.provider.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.ToolDefinition;

/**
 * Regression tests for model-specific streaming policy decisions.
 * <p>
 * Context: MiniMax M2 models return 400 "tool result's tool id(...) not found (2013)"
 * on the follow-up request after a tool call when the first response was streamed,
 * because the streaming aggregation of {@code tool_call_id} can diverge from what
 * the server stored. Non-stream mode preserves the id verbatim in
 * {@code DynamicLlmProvider.parseToolCalls}.
 */
public class OpenAiModelCompatibilityPolicyTest {

    private final OpenAiModelCompatibilityPolicy policy = new OpenAiModelCompatibilityPolicy();

    @Test
    public void miniMaxM27ViaCodePilotBackendUsesNonStreamForToolCalls() {
        LlmProviderConfig config = configured(ProviderType.CODEPILOT_BACKEND, "minimax-m2.7"); //$NON-NLS-1$
        LlmRequest request = requestWithTool();

        ProviderExecutionPlan plan = policy.plan(config, request, true);

        assertFalse("minimax-m2.7 tool call must not stream via CodePilot backend", plan.isStreaming()); //$NON-NLS-1$
        assertNotNull(plan.getReason());
        assertTrue(plan.getReason(), plan.getReason().toLowerCase().contains("minimax m2")); //$NON-NLS-1$
    }

    @Test
    public void miniMaxM25ViaCodePilotBackendUsesNonStreamForToolCalls() {
        LlmProviderConfig config = configured(ProviderType.CODEPILOT_BACKEND, "minimax-m2.5"); //$NON-NLS-1$
        LlmRequest request = requestWithTool();

        ProviderExecutionPlan plan = policy.plan(config, request, true);

        assertFalse(plan.isStreaming());
    }

    @Test
    public void miniMaxM27ViaGenericOpenAiCompatibleUsesNonStreamForToolCalls() {
        LlmProviderConfig config = configured(ProviderType.OPENAI_COMPATIBLE, "minimax-m2.7"); //$NON-NLS-1$
        LlmRequest request = requestWithTool();

        ProviderExecutionPlan plan = policy.plan(config, request, true);

        assertFalse("minimax-m2.7 tool call must not stream via generic OpenAI-compatible", //$NON-NLS-1$
                plan.isStreaming());
        assertNotNull(plan.getReason());
        assertTrue(plan.getReason(), plan.getReason().toLowerCase().contains("minimax m2")); //$NON-NLS-1$
    }

    @Test
    public void miniMaxCaseInsensitiveMatchesM2() {
        LlmProviderConfig config = configured(ProviderType.CODEPILOT_BACKEND, "MiniMax-M2.7"); //$NON-NLS-1$
        LlmRequest request = requestWithTool();

        ProviderExecutionPlan plan = policy.plan(config, request, true);

        assertFalse(plan.isStreaming());
    }

    /**
     * Narrower than {@code contains("minimax")} by design: do not pre-emptively catch future
     * families without evidence of the same tool_call_id quirk.
     */
    @Test
    public void miniMaxM3DoesNotFallUnderM2Policy() {
        LlmProviderConfig config = configured(ProviderType.OPENAI_COMPATIBLE, "minimax-m3.0"); //$NON-NLS-1$
        LlmRequest request = requestWithTool();

        ProviderExecutionPlan plan = policy.plan(config, request, true);

        assertTrue("hypothetical m3.0 must not be scoped by m2 policy", plan.isStreaming()); //$NON-NLS-1$
    }

    /** Token boundary: a two-digit suffix (m20) must not be scoped by m2 policy. */
    @Test
    public void miniMaxM20DoesNotFallUnderM2Policy() {
        LlmProviderConfig config = configured(ProviderType.OPENAI_COMPATIBLE, "minimax-m20"); //$NON-NLS-1$
        LlmRequest request = requestWithTool();

        ProviderExecutionPlan plan = policy.plan(config, request, true);

        assertTrue("minimax-m20 must not be matched as m2", plan.isStreaming()); //$NON-NLS-1$
    }

    /** Left token boundary: substring match inside another word must not trigger. */
    @Test
    public void stringContainingSubstringDoesNotFallUnderM2Policy() {
        LlmProviderConfig config = configured(ProviderType.OPENAI_COMPATIBLE, "notminimax-m2"); //$NON-NLS-1$
        LlmRequest request = requestWithTool();

        ProviderExecutionPlan plan = policy.plan(config, request, true);

        assertTrue("substring inside another identifier must not trigger m2 policy", //$NON-NLS-1$
                plan.isStreaming());
    }

    @Test
    public void miniMaxM27BackendWithoutToolsAllowsStreaming() {
        LlmProviderConfig config = configured(ProviderType.CODEPILOT_BACKEND, "minimax-m2.7"); //$NON-NLS-1$
        LlmRequest request = LlmRequest.builder()
                .userMessage("hi") //$NON-NLS-1$
                .build();

        ProviderExecutionPlan plan = policy.plan(config, request, true);

        assertTrue("streaming must remain available when no tools are requested", plan.isStreaming()); //$NON-NLS-1$
    }

    // --- Guard tests: other families must keep their previous behavior. ---

    @Test
    public void qwenBackendKeepsStreamingWithTools() {
        LlmProviderConfig config = configured(ProviderType.CODEPILOT_BACKEND, "qwen3-coder-plus"); //$NON-NLS-1$
        LlmRequest request = requestWithTool();

        ProviderExecutionPlan plan = policy.plan(config, request, true);

        assertTrue(plan.isStreaming());
        assertEquals(0.3, plan.getRequestOverrides().get("temperature").getAsDouble(), 0.0001); //$NON-NLS-1$
        assertFalse(plan.getRequestOverrides().get("enable_thinking").getAsBoolean()); //$NON-NLS-1$
        assertFalse(plan.getRequestOverrides().get("parallel_tool_calls").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void moonshotBackendKeepsStreamingWithTools() {
        LlmProviderConfig config = configured(ProviderType.CODEPILOT_BACKEND, "moonshot-v1-8k"); //$NON-NLS-1$
        LlmRequest request = requestWithTool();

        ProviderExecutionPlan plan = policy.plan(config, request, true);

        assertTrue(plan.isStreaming());
        assertEquals(0.6, plan.getRequestOverrides().get("temperature").getAsDouble(), 0.0001); //$NON-NLS-1$
    }

    @Test
    public void kimiBackendKeepsStreamingWithTools() {
        LlmProviderConfig config = configured(ProviderType.CODEPILOT_BACKEND, "kimi-k2.5-instruct"); //$NON-NLS-1$
        LlmRequest request = requestWithTool();

        ProviderExecutionPlan plan = policy.plan(config, request, true);

        assertTrue(plan.isStreaming());
        assertEquals(0.6, plan.getRequestOverrides().get("temperature").getAsDouble(), 0.0001); //$NON-NLS-1$
        assertFalse(plan.getRequestOverrides().get("parallel_tool_calls").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void deepSeekBackendUsesNonStreamForToolCalls() {
        LlmProviderConfig config = configured(ProviderType.CODEPILOT_BACKEND, "deepseek-v4-flash"); //$NON-NLS-1$
        LlmRequest request = requestWithTool();

        ProviderExecutionPlan plan = policy.plan(config, request, true);

        assertFalse("DeepSeek tool-call responses must be replay-safe for reasoning_content", //$NON-NLS-1$
                plan.isStreaming());
        assertNotNull(plan.getReason());
        assertTrue(plan.getReason(),
                plan.getReason().toLowerCase().contains("deepseek")); //$NON-NLS-1$
        assertFalse("parallel_tool_calls must stay disabled on CodePilot backend", //$NON-NLS-1$
                plan.getRequestOverrides().get("parallel_tool_calls").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void deepSeekBackendWithoutToolsAllowsStreaming() {
        LlmProviderConfig config = configured(ProviderType.CODEPILOT_BACKEND, "deepseek-v4-flash"); //$NON-NLS-1$
        LlmRequest request = LlmRequest.builder()
                .userMessage("hi") //$NON-NLS-1$
                .build();

        ProviderExecutionPlan plan = policy.plan(config, request, true);

        assertTrue("non-tool DeepSeek calls do not need tool-call reasoning replay", //$NON-NLS-1$
                plan.isStreaming());
    }

    @Test
    public void unknownBackendModelKeepsStreamingWithTools() {
        LlmProviderConfig config = configured(ProviderType.CODEPILOT_BACKEND, "some-new-model"); //$NON-NLS-1$
        LlmRequest request = requestWithTool();

        ProviderExecutionPlan plan = policy.plan(config, request, true);

        assertTrue(plan.isStreaming());
    }

    /**
     * Workspace logs (2026-04-23) showed the streaming request to
     * {@code https://api.codepilot1c.ru/v1/chat/completions} hang 120 seconds with zero SSE
     * chunks when the client sent {@code model="auto"} + 58 tool definitions. The backend
     * resolves {@code "auto"} to kimi-k2.5 / minimax-m2.7 server-side and reasoning-thinks
     * before flushing the first delta, but the client read-timeout fires before any byte
     * arrives. Non-stream replay returns in 5–7s and preserves prompt cache hits
     * (92–99% observed in billing data), so we force non-stream on auto-routed tool calls.
     */
    @Test
    public void autoRoutedCodePilotBackendWithToolsUsesNonStream() {
        LlmProviderConfig config = configured(ProviderType.CODEPILOT_BACKEND, "auto"); //$NON-NLS-1$
        LlmRequest request = requestWithTool();

        ProviderExecutionPlan plan = policy.plan(config, request, true);

        assertFalse("auto routing must not stream tool calls on CodePilot backend", //$NON-NLS-1$
                plan.isStreaming());
        assertNotNull(plan.getReason());
        assertTrue(plan.getReason(),
                plan.getReason().toLowerCase().contains("auto routing")); //$NON-NLS-1$
        assertFalse("parallel_tool_calls must stay disabled on CodePilot backend", //$NON-NLS-1$
                plan.getRequestOverrides().get("parallel_tool_calls").getAsBoolean()); //$NON-NLS-1$
    }

    /** Auto routing without tools may still stream — only tool-call requests hang. */
    @Test
    public void autoRoutedCodePilotBackendWithoutToolsStillStreams() {
        LlmProviderConfig config = configured(ProviderType.CODEPILOT_BACKEND, "auto"); //$NON-NLS-1$
        LlmRequest request = LlmRequest.builder()
                .userMessage("hi") //$NON-NLS-1$
                .build();

        ProviderExecutionPlan plan = policy.plan(config, request, true);

        assertTrue("non-tool auto-routed calls must keep streaming", plan.isStreaming()); //$NON-NLS-1$
    }

    /** Case-insensitive match: {@code model="Auto"} must trip the same non-stream path. */
    @Test
    public void autoRoutedCaseInsensitiveMatches() {
        LlmProviderConfig config = configured(ProviderType.CODEPILOT_BACKEND, "Auto"); //$NON-NLS-1$
        LlmRequest request = requestWithTool();

        ProviderExecutionPlan plan = policy.plan(config, request, true);

        assertFalse(plan.isStreaming());
    }

    /** The auto fix must not leak into generic OpenAI-compatible configs. */
    @Test
    public void autoRoutedOnGenericOpenAiCompatibleStillStreams() {
        LlmProviderConfig config = configured(ProviderType.OPENAI_COMPATIBLE, "auto"); //$NON-NLS-1$
        LlmRequest request = requestWithTool();

        ProviderExecutionPlan plan = policy.plan(config, request, true);

        assertTrue("non-CodePilot providers must not be affected by backend SSE quirks", //$NON-NLS-1$
                plan.isStreaming());
    }

    @Test
    public void glm5KeepsNonStreamForToolCalls() {
        LlmProviderConfig config = configured(ProviderType.OPENAI_COMPATIBLE, "glm-5-turbo"); //$NON-NLS-1$
        LlmRequest request = requestWithTool();

        ProviderExecutionPlan plan = policy.plan(config, request, true);

        assertFalse(plan.isStreaming());
    }

    private static LlmRequest requestWithTool() {
        ToolDefinition tool = ToolDefinition.builder()
                .name("noop") //$NON-NLS-1$
                .description("noop") //$NON-NLS-1$
                .parametersSchema("{\"type\":\"object\"}") //$NON-NLS-1$
                .build();
        return LlmRequest.builder()
                .messages(List.of(LlmMessage.user("go"))) //$NON-NLS-1$
                .tools(List.of(tool))
                .build();
    }

    private static LlmProviderConfig configured(ProviderType type, String model) {
        LlmProviderConfig config = new LlmProviderConfig();
        config.setType(type);
        config.setModel(model);
        config.setStreamingEnabled(true);
        return config;
    }
}
