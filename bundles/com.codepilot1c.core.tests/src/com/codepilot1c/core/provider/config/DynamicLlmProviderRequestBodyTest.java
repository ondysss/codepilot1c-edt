package com.codepilot1c.core.provider.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.model.LlmAttachment;
import com.codepilot1c.core.model.LlmContentPart;
import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.ToolCall;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class DynamicLlmProviderRequestBodyTest {

    @Test
    public void ollamaRequestEmbedsImageAttachmentsAsBase64() throws Exception {
        Path image = Files.createTempFile("dynamic-provider-ollama", ".png"); //$NON-NLS-1$ //$NON-NLS-2$
        try {
            Files.write(image, new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47 });

            LlmAttachment attachment = LlmAttachment.builder()
                    .kind(LlmAttachment.Kind.IMAGE)
                    .displayName("diagram.png") //$NON-NLS-1$
                    .mimeType("image/png") //$NON-NLS-1$
                    .cachePath(image.toString())
                    .sizeBytes(Files.size(image))
                    .build();
            LlmMessage message = LlmMessage.user(List.of(
                    LlmContentPart.text("Опиши диаграмму"), //$NON-NLS-1$
                    LlmContentPart.image(attachment)));
            LlmRequest request = LlmRequest.builder()
                    .addMessage(message)
                    .model("llama3.2-vision") //$NON-NLS-1$
                    .build();

            DynamicLlmProvider provider = new DynamicLlmProvider(configured(ProviderType.OLLAMA, "llama3.2-vision")); //$NON-NLS-1$
            Method method = DynamicLlmProvider.class.getDeclaredMethod("buildOllamaRequestBody", LlmRequest.class, boolean.class); //$NON-NLS-1$
            method.setAccessible(true);

            String requestBody = (String) method.invoke(provider, request, false);
            JsonObject body = JsonParser.parseString(requestBody).getAsJsonObject();
            JsonArray messages = body.getAsJsonArray("messages"); //$NON-NLS-1$
            JsonObject first = messages.get(0).getAsJsonObject();

            assertEquals("Опиши диаграмму\n\n[Image: diagram.png (image/png)]", first.get("content").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
            JsonArray images = first.getAsJsonArray("images"); //$NON-NLS-1$
            assertEquals(1, images.size());
            assertTrue(images.get(0).getAsString().length() > 0);
        } finally {
            Files.deleteIfExists(image);
        }
    }

    @Test
    public void openAiRequestPreservesAssistantReasoningContentWithoutToolCalls() throws Exception {
        LlmRequest request = LlmRequest.builder()
                .addMessage(LlmMessage.user("next")) //$NON-NLS-1$
                .addMessage(LlmMessage.assistant("visible answer", "private reasoning")) //$NON-NLS-1$ //$NON-NLS-2$
                .build();

        DynamicLlmProvider provider = new DynamicLlmProvider(configured(ProviderType.OPENAI_COMPATIBLE, "deepseek-v4-pro")); //$NON-NLS-1$
        Method method = DynamicLlmProvider.class.getDeclaredMethod(
                "buildOpenAiRequestBody", LlmRequest.class, ProviderExecutionPlan.class); //$NON-NLS-1$
        method.setAccessible(true);

        String requestBody = (String) method.invoke(provider, request, ProviderExecutionPlan.streaming(false));
        JsonObject body = JsonParser.parseString(requestBody).getAsJsonObject();
        JsonArray messages = body.getAsJsonArray("messages"); //$NON-NLS-1$
        JsonObject assistant = messages.get(1).getAsJsonObject();

        assertEquals("assistant", assistant.get("role").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("visible answer", assistant.get("content").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("private reasoning", assistant.get("reasoning_content").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void openAiRequestPreservesEmptyAssistantReasoningContentWithToolCalls() throws Exception {
        LlmRequest request = LlmRequest.builder()
                .addMessage(LlmMessage.user("continue")) //$NON-NLS-1$
                .addMessage(LlmMessage.assistantWithToolCalls(null, "", List.of( //$NON-NLS-1$
                        new ToolCall("call-1", "read_file", "{\"path\":\"README.md\"}")))) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .addMessage(LlmMessage.toolResult("call-1", "file content")) //$NON-NLS-1$ //$NON-NLS-2$
                .build();

        DynamicLlmProvider provider = new DynamicLlmProvider(configured(ProviderType.OPENAI_COMPATIBLE, "deepseek-v4-flash")); //$NON-NLS-1$
        Method method = DynamicLlmProvider.class.getDeclaredMethod(
                "buildOpenAiRequestBody", LlmRequest.class, ProviderExecutionPlan.class); //$NON-NLS-1$
        method.setAccessible(true);

        String requestBody = (String) method.invoke(provider, request, ProviderExecutionPlan.streaming(false));
        JsonObject body = JsonParser.parseString(requestBody).getAsJsonObject();
        JsonObject assistant = body.getAsJsonArray("messages").get(1).getAsJsonObject(); //$NON-NLS-1$

        assertEquals("assistant", assistant.get("role").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(assistant.has("reasoning_content")); //$NON-NLS-1$
        assertEquals("", assistant.get("reasoning_content").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("read_file", assistant.getAsJsonArray("tool_calls").get(0).getAsJsonObject() //$NON-NLS-1$ //$NON-NLS-2$
                .getAsJsonObject("function").get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static LlmProviderConfig configured(ProviderType type, String model) {
        LlmProviderConfig config = new LlmProviderConfig();
        config.setId("test-" + type.name()); //$NON-NLS-1$
        config.setName("test-" + type.name()); //$NON-NLS-1$
        config.setType(type);
        config.setBaseUrl("http://localhost:11434"); //$NON-NLS-1$
        config.setApiKey("key"); //$NON-NLS-1$
        config.setModel(model);
        return config;
    }
}
