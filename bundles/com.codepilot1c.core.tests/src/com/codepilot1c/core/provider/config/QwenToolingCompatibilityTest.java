package com.codepilot1c.core.provider.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Test;

import com.codepilot1c.core.agent.profiles.AgentProfile;
import com.codepilot1c.core.agent.profiles.AgentProfileRegistry;
import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.ToolDefinition;
import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.provider.ProviderCapabilities;
import com.codepilot1c.core.tools.ToolRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class QwenToolingCompatibilityTest {

    private static final Pattern PARAM_PATTERN = Pattern.compile("<parameter=([^>]+)>"); //$NON-NLS-1$

    private final LlmProviderConfigStore store = LlmProviderConfigStore.getInstance();

    @After
    public void cleanup() throws Exception {
        setStoreState(null, null);
    }

    @Test
    public void transportUsesDualModeForNewBslTools() {
        ToolDefinition tool = ToolDefinition.builder()
                .name("bsl_module_context") //$NON-NLS-1$
                .description("Return BSL module type, owner, default pragmas, and method counts for one module.") //$NON-NLS-1$
                .parametersSchema("""
                        {"type":"object","properties":{"projectName":{"type":"string"},"filePath":{"type":"string"}},"required":["projectName","filePath"]}
                        """)
                .build();

        LlmRequest request = LlmRequest.builder()
                .systemMessage("You are a coding agent.") //$NON-NLS-1$
                .userMessage("Inspect module context.") //$NON-NLS-1$
                .addTool(tool)
                .build();

        LlmProviderConfig config = new LlmProviderConfig();
        config.setType(ProviderType.CODEPILOT_BACKEND);
        config.setModel("qwen3-coder-plus"); //$NON-NLS-1$
        config.setStreamingEnabled(true);

        ProviderCapabilities caps = ProviderCapabilities.builder()
                .codePilotBackend(true)
                .backendOptimizations(true)
                .resolvedModel(true)
                .resolvedModelFamily(ProviderCapabilities.FAMILY_QWEN_CODER)
                .defaultTemperature(ProviderCapabilities.QWEN_DEFAULT_TEMPERATURE)
                .build();

        String json = new QwenFunctionCallingTransport(new Gson()).buildRequestBody(
                request,
                ProviderExecutionPlan.streaming(true),
                config,
                caps);

        JsonObject body = JsonParser.parseString(json).getAsJsonObject();
        JsonArray messages = body.getAsJsonArray("messages"); //$NON-NLS-1$
        JsonArray tools = body.getAsJsonArray("tools"); //$NON-NLS-1$
        String systemContent = messages.get(0).getAsJsonObject().get("content").getAsString(); //$NON-NLS-1$

        assertTrue(systemContent.contains("<function=bsl_module_context>")); //$NON-NLS-1$
        assertTrue(systemContent.contains("<parameter=projectName>DemoConfiguration</parameter>")); //$NON-NLS-1$
        assertEquals("bsl_module_context", tools.get(0).getAsJsonObject() //$NON-NLS-1$
                .getAsJsonObject("function").get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(body.get("enable_thinking").getAsBoolean()); //$NON-NLS-1$
        assertFalse(body.get("parallel_tool_calls").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void transportPreservesEmptyReasoningContentForToolCallReplay() {
        LlmRequest request = LlmRequest.builder()
                .messages(List.of(
                        LlmMessage.user("continue"), //$NON-NLS-1$
                        LlmMessage.assistantWithToolCalls(null, "", List.of( //$NON-NLS-1$
                                new ToolCall("call-1", "read_file", "{\"path\":\"README.md\"}"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        LlmMessage.toolResult("call-1", "file content"))) //$NON-NLS-1$ //$NON-NLS-2$
                .build();

        LlmProviderConfig config = new LlmProviderConfig();
        config.setType(ProviderType.CODEPILOT_BACKEND);
        config.setModel("qwen3-coder-plus"); //$NON-NLS-1$

        ProviderCapabilities caps = ProviderCapabilities.builder()
                .codePilotBackend(true)
                .backendOptimizations(true)
                .resolvedModel(true)
                .resolvedModelFamily(ProviderCapabilities.FAMILY_QWEN_CODER)
                .defaultTemperature(ProviderCapabilities.QWEN_DEFAULT_TEMPERATURE)
                .build();

        String json = new QwenFunctionCallingTransport(new Gson()).buildRequestBody(
                request,
                ProviderExecutionPlan.streaming(false),
                config,
                caps);

        JsonObject body = JsonParser.parseString(json).getAsJsonObject();
        JsonObject assistant = body.getAsJsonArray("messages").get(1).getAsJsonObject(); //$NON-NLS-1$

        assertTrue(assistant.has("reasoning_content")); //$NON-NLS-1$
        assertEquals("", assistant.get("reasoning_content").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void compatibilityPolicyDisablesStreamingForLargeQwenToolResults() {
        LlmProviderConfig config = new LlmProviderConfig();
        config.setType(ProviderType.CODEPILOT_BACKEND);
        config.setModel("qwen3-coder-plus"); //$NON-NLS-1$
        config.setStreamingEnabled(true);

        ToolDefinition tool = ToolDefinition.builder()
                .name("read_file") //$NON-NLS-1$
                .description("Read a file") //$NON-NLS-1$
                .parametersSchema("{\"type\":\"object\"}") //$NON-NLS-1$
                .build();

        String largePayload = "x".repeat(50_001); //$NON-NLS-1$
        LlmRequest request = LlmRequest.builder()
                .messages(List.of(
                        LlmMessage.user("continue"), //$NON-NLS-1$
                        LlmMessage.toolResult("call-1", largePayload))) //$NON-NLS-1$
                .tools(List.of(tool))
                .build();

        ProviderExecutionPlan plan = new OpenAiModelCompatibilityPolicy().plan(config, request, true);

        assertFalse(plan.isStreaming());
        assertTrue(plan.getReason().contains("large tool result")); //$NON-NLS-1$
        assertEquals(0.3, plan.getRequestOverrides().get("temperature").getAsDouble(), 0.0001); //$NON-NLS-1$
    }

    @Test
    public void transportIncludesXmlPrimingForDelegateToAgentTool() {
        ToolDefinition tool = ToolDefinition.builder()
                .name("delegate_to_agent") //$NON-NLS-1$
                .description("Delegate a concrete task to a specialized sub-agent.") //$NON-NLS-1$
                .parametersSchema("""
                        {"type":"object","properties":{"agentType":{"type":"string"},"task":{"type":"string"},"context":{"type":"string"}},"required":["task"]}
                        """)
                .build();

        LlmRequest request = LlmRequest.builder()
                .systemMessage("You are an orchestrator.") //$NON-NLS-1$
                .userMessage("Create catalog and tests.") //$NON-NLS-1$
                .addTool(tool)
                .build();

        LlmProviderConfig config = new LlmProviderConfig();
        config.setType(ProviderType.CODEPILOT_BACKEND);
        config.setModel("qwen3-coder-plus"); //$NON-NLS-1$

        ProviderCapabilities caps = ProviderCapabilities.builder()
                .codePilotBackend(true)
                .backendOptimizations(true)
                .resolvedModel(true)
                .resolvedModelFamily(ProviderCapabilities.FAMILY_QWEN_CODER)
                .defaultTemperature(ProviderCapabilities.QWEN_DEFAULT_TEMPERATURE)
                .build();

        String json = new QwenFunctionCallingTransport(new Gson()).buildRequestBody(
                request,
                ProviderExecutionPlan.streaming(false),
                config,
                caps);

        JsonObject body = JsonParser.parseString(json).getAsJsonObject();
        String systemContent = body.getAsJsonArray("messages") //$NON-NLS-1$
                .get(0).getAsJsonObject()
                .get("content").getAsString(); //$NON-NLS-1$

        assertTrue(systemContent.contains("<function=delegate_to_agent>")); //$NON-NLS-1$
        assertTrue(systemContent.contains("<parameter=agentType>metadata</parameter>")); //$NON-NLS-1$
        assertTrue(systemContent.contains("<parameter=task>Create catalog Items and list form</parameter>")); //$NON-NLS-1$
    }

    @Test
    public void everyBackendVisibleBuiltInToolHasSchemaAlignedQwenExample() throws Exception {
        setStoreState(List.of(configured("backend", ProviderType.CODEPILOT_BACKEND)), "backend"); //$NON-NLS-1$ //$NON-NLS-2$

        ToolRegistry registry = ToolRegistry.getInstance();
        Map<String, ToolDefinition> definitionsByName = new TreeMap<>();
        for (AgentProfile profile : AgentProfileRegistry.getInstance().getAllProfiles()) {
            List<ToolDefinition> definitions = registry.getToolDefinitions(
                    registry.createRuntimeSurfaceContext(profile));
            for (ToolDefinition definition : definitions) {
                definitionsByName.putIfAbsent(definition.getName(), definition);
            }
        }

        ProviderCapabilities caps = ProviderCapabilities.builder()
                .codePilotBackend(true)
                .backendOptimizations(true)
                .resolvedModel(true)
                .resolvedModelFamily(ProviderCapabilities.FAMILY_QWEN_CODER)
                .defaultTemperature(ProviderCapabilities.QWEN_DEFAULT_TEMPERATURE)
                .build();

        List<String> failures = new ArrayList<>();
        for (ToolDefinition definition : definitionsByName.values()) {
            JsonObject schema = JsonParser.parseString(definition.getParametersSchema()).getAsJsonObject();
            Set<String> schemaProperties = schema.has("properties") //$NON-NLS-1$
                    ? schema.getAsJsonObject("properties").keySet() //$NON-NLS-1$
                    : Set.of();
            Set<String> required = new TreeSet<>();
            if (schema.has("required")) { //$NON-NLS-1$
                for (var element : schema.getAsJsonArray("required")) { //$NON-NLS-1$
                    required.add(element.getAsString());
                }
            }

            String examples = QwenToolCallExamples.getExamples(caps, List.of(definition));
            if (!examples.contains("<function=" + definition.getName() + ">")) { //$NON-NLS-1$ //$NON-NLS-2$
                failures.add(definition.getName() + ": missing XML example"); //$NON-NLS-1$
                continue;
            }

            Set<String> exampleParams = extractExampleParams(examples);
            for (String requiredKey : required) {
                if (!exampleParams.contains(requiredKey)) {
                    failures.add(definition.getName() + ": missing required example param " + requiredKey); //$NON-NLS-1$
                }
            }
            if (!schemaProperties.isEmpty()) {
                for (String exampleParam : exampleParams) {
                    if (!schemaProperties.contains(exampleParam)) {
                        failures.add(definition.getName() + ": example param not in schema: " + exampleParam); //$NON-NLS-1$
                    }
                }
            } else if (!exampleParams.isEmpty()) {
                failures.add(definition.getName() + ": example has params but schema has no properties"); //$NON-NLS-1$
            }
        }

        assertTrue("Qwen example/schema mismatches:\n" + String.join("\n", failures), failures.isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void extensionManageQwenExampleShowsBaseAndExtensionProjectContract() {
        ToolDefinition definition = ToolDefinition.builder()
                .name("extension_manage") //$NON-NLS-1$
                .description("Управляет расширениями EDT.") //$NON-NLS-1$
                .parametersSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "command": {"type": "string"},
                            "project": {"type": "string"},
                            "base_project": {"type": "string"},
                            "extension_project": {"type": "string"},
                            "source_object_fqn": {"type": "string"},
                            "validation_token": {"type": "string"}
                          },
                          "required": ["command"]
                        }
                        """)
                .build();
        ProviderCapabilities caps = ProviderCapabilities.builder()
                .codePilotBackend(true)
                .backendOptimizations(true)
                .resolvedModel(true)
                .resolvedModelFamily(ProviderCapabilities.FAMILY_QWEN_CODER)
                .defaultTemperature(ProviderCapabilities.QWEN_DEFAULT_TEMPERATURE)
                .build();

        String examples = QwenToolCallExamples.getExamples(caps, List.of(definition));

        assertTrue(examples.contains("<function=extension_manage>")); //$NON-NLS-1$
        assertTrue(examples.contains("<parameter=command>adopt</parameter>")); //$NON-NLS-1$
        assertTrue(examples.contains("<parameter=project>DemoConfiguration</parameter>")); //$NON-NLS-1$
        assertTrue(examples.contains("<parameter=base_project>DemoConfiguration</parameter>")); //$NON-NLS-1$
        assertTrue(examples.contains("<parameter=extension_project>ExtensionDemo</parameter>")); //$NON-NLS-1$
        assertTrue(examples.contains("<parameter=source_object_fqn>Catalog.Items</parameter>")); //$NON-NLS-1$
        assertTrue(examples.contains("<parameter=validation_token>validation-token-123</parameter>")); //$NON-NLS-1$
    }

    private Set<String> extractExampleParams(String examples) {
        Set<String> params = new TreeSet<>();
        Matcher matcher = PARAM_PATTERN.matcher(examples);
        while (matcher.find()) {
            params.add(matcher.group(1));
        }
        return params;
    }

    private void setStoreState(List<LlmProviderConfig> configs, String activeProviderId) throws Exception {
        Field configsField = LlmProviderConfigStore.class.getDeclaredField("cachedConfigs"); //$NON-NLS-1$
        configsField.setAccessible(true);
        configsField.set(store, configs);

        Field activeField = LlmProviderConfigStore.class.getDeclaredField("cachedActiveProviderId"); //$NON-NLS-1$
        activeField.setAccessible(true);
        activeField.set(store, activeProviderId);
    }

    private static LlmProviderConfig configured(String id, ProviderType type) {
        LlmProviderConfig config = new LlmProviderConfig();
        config.setId(id);
        config.setName(id);
        config.setType(type);
        config.setBaseUrl("https://example.com/v1"); //$NON-NLS-1$
        config.setApiKey("key"); //$NON-NLS-1$
        config.setModel("qwen3-coder-plus"); //$NON-NLS-1$
        return config;
    }
}
