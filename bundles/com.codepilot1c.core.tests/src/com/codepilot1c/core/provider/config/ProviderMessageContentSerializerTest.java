package com.codepilot1c.core.provider.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.model.LlmAttachment;
import com.codepilot1c.core.model.LlmContentPart;
import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.provider.ProviderCapabilities;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class ProviderMessageContentSerializerTest {

    @Test
    public void openAiSerializerEmitsNativeImageBlockForSupportedProviders() throws Exception {
        Path image = Files.createTempFile("provider-message-content", ".png"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.write(image, new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47 });

        LlmAttachment attachment = LlmAttachment.builder()
                .kind(LlmAttachment.Kind.IMAGE)
                .displayName("diagram.png") //$NON-NLS-1$
                .mimeType("image/png") //$NON-NLS-1$
                .cachePath(image.toString())
                .sizeBytes(Files.size(image))
                .build();
        LlmMessage message = LlmMessage.user(List.of(
                LlmContentPart.text("Опиши схему"), //$NON-NLS-1$
                LlmContentPart.image(attachment)));

        JsonArray content = ProviderMessageContentSerializer.toOpenAiContent(
                message,
                ProviderCapabilities.builder().imageInput(true).build()).getAsJsonArray();

        assertEquals(2, content.size());
        assertEquals("text", content.get(0).getAsJsonObject().get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject imageBlock = content.get(1).getAsJsonObject();
        assertEquals("image_url", imageBlock.get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(imageBlock.getAsJsonObject("image_url").get("url").getAsString() //$NON-NLS-1$ //$NON-NLS-2$
                .startsWith("data:image/png;base64,")); //$NON-NLS-1$
    }

    @Test
    public void openAiSerializerFallsBackToTextForFiles() throws Exception {
        Path file = Files.createTempFile("provider-message-content", ".txt"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.writeString(file, "line1\nline2", StandardCharsets.UTF_8); //$NON-NLS-1$

        LlmAttachment attachment = LlmAttachment.builder()
                .kind(LlmAttachment.Kind.FILE)
                .displayName("notes.txt") //$NON-NLS-1$
                .mimeType("text/plain") //$NON-NLS-1$
                .originalPath(file.toString())
                .previewText("line1\nline2") //$NON-NLS-1$
                .sizeBytes(Files.size(file))
                .build();
        LlmMessage message = LlmMessage.user(List.of(LlmContentPart.file(attachment)));

        JsonArray content = ProviderMessageContentSerializer.toOpenAiContent(
                message,
                ProviderCapabilities.none()).getAsJsonArray();

        assertEquals(1, content.size());
        assertEquals("text", content.get(0).getAsJsonObject().get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        String fallback = content.get(0).getAsJsonObject().get("text").getAsString(); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(fallback.contains("[File: notes.txt (text/plain)]")); //$NON-NLS-1$
        assertTrue(fallback.contains("line1")); //$NON-NLS-1$
    }
}
