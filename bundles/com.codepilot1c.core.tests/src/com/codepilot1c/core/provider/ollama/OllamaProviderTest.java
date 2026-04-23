package com.codepilot1c.core.provider.ollama;

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
import com.codepilot1c.core.provider.ProviderCapabilities;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class OllamaProviderTest {

    @Test
    public void capabilitiesAdvertiseMultimodalSupport() {
        ProviderCapabilities caps = new OllamaProvider().getCapabilities();

        assertTrue(caps.supportsImageInput());
        assertTrue(caps.supportsDocumentInput());
        assertTrue(caps.supportsAttachmentMetadata());
    }

    @Test
    public void requestBodyEmbedsImageAttachmentsAsBase64() throws Exception {
        Path image = Files.createTempFile("ollama-provider", ".png"); //$NON-NLS-1$ //$NON-NLS-2$
        try {
            Files.write(image, new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47 });

            LlmAttachment attachment = LlmAttachment.builder()
                    .kind(LlmAttachment.Kind.IMAGE)
                    .displayName("clipboard.png") //$NON-NLS-1$
                    .mimeType("image/png") //$NON-NLS-1$
                    .cachePath(image.toString())
                    .sizeBytes(Files.size(image))
                    .build();
            LlmMessage message = LlmMessage.user(List.of(
                    LlmContentPart.text("Что на скриншоте?"), //$NON-NLS-1$
                    LlmContentPart.image(attachment)));
            LlmRequest request = LlmRequest.builder()
                    .addMessage(message)
                    .model("llama3.2-vision") //$NON-NLS-1$
                    .build();

            OllamaProvider provider = new OllamaProvider();
            Method method = OllamaProvider.class.getDeclaredMethod("buildRequestBody", LlmRequest.class, boolean.class); //$NON-NLS-1$
            method.setAccessible(true);

            String requestBody = (String) method.invoke(provider, request, false);
            JsonObject body = JsonParser.parseString(requestBody).getAsJsonObject();
            JsonArray messages = body.getAsJsonArray("messages"); //$NON-NLS-1$
            JsonObject first = messages.get(0).getAsJsonObject();

            assertEquals("Что на скриншоте?\n\n[Image: clipboard.png (image/png)]", first.get("content").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
            JsonArray images = first.getAsJsonArray("images"); //$NON-NLS-1$
            assertEquals(1, images.size());
            assertTrue(images.get(0).getAsString().length() > 0);
        } finally {
            Files.deleteIfExists(image);
        }
    }
}
