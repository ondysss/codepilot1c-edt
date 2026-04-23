/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import com.codepilot1c.core.model.LlmAttachment;
import com.codepilot1c.core.model.LlmContentPart;
import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.provider.ProviderCapabilities;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Shared serializers for multimodal message content.
 */
public final class ProviderMessageContentSerializer {

    private ProviderMessageContentSerializer() {
    }

    public static JsonElement toOpenAiContent(LlmMessage message, ProviderCapabilities caps) {
        if (!message.hasContentParts()) {
            return new JsonPrimitive(message.getContent());
        }

        JsonArray content = new JsonArray();
        for (LlmContentPart part : message.getContentParts()) {
            if (part.isText()) {
                appendTextBlock(content, part.getText());
            } else if (part.isImage() && caps.supportsImageInput()) {
                JsonObject imageBlock = buildOpenAiImageBlock(part.getAttachment());
                if (imageBlock != null) {
                    content.add(imageBlock);
                } else {
                    appendTextBlock(content, part.toTextFallback());
                }
            } else {
                appendTextBlock(content, part.toTextFallback());
            }
        }
        if (content.size() == 0) {
            return new JsonPrimitive(message.getTextualContentFallback());
        }
        return content;
    }

    public static JsonArray toAnthropicContent(LlmMessage message, ProviderCapabilities caps) {
        JsonArray content = new JsonArray();
        if (!message.hasContentParts()) {
            appendAnthropicTextBlock(content, message.getContent());
            return content;
        }
        for (LlmContentPart part : message.getContentParts()) {
            if (part.isText()) {
                appendAnthropicTextBlock(content, part.getText());
            } else if (part.isImage() && caps.supportsImageInput()) {
                JsonObject imageBlock = buildAnthropicImageBlock(part.getAttachment());
                if (imageBlock != null) {
                    content.add(imageBlock);
                } else {
                    appendAnthropicTextBlock(content, part.toTextFallback());
                }
            } else {
                appendAnthropicTextBlock(content, part.toTextFallback());
            }
        }
        if (content.size() == 0) {
            appendAnthropicTextBlock(content, message.getTextualContentFallback());
        }
        return content;
    }

    private static void appendTextBlock(JsonArray target, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text"); //$NON-NLS-1$ //$NON-NLS-2$
        textBlock.addProperty("text", text); //$NON-NLS-1$
        target.add(textBlock);
    }

    private static void appendAnthropicTextBlock(JsonArray target, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text"); //$NON-NLS-1$ //$NON-NLS-2$
        textBlock.addProperty("text", text); //$NON-NLS-1$
        target.add(textBlock);
    }

    private static JsonObject buildOpenAiImageBlock(LlmAttachment attachment) {
        String dataUri = toDataUri(attachment);
        if (dataUri == null) {
            return null;
        }
        JsonObject imageBlock = new JsonObject();
        imageBlock.addProperty("type", "image_url"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", dataUri); //$NON-NLS-1$
        imageUrl.addProperty("detail", "auto"); //$NON-NLS-1$ //$NON-NLS-2$
        imageBlock.add("image_url", imageUrl); //$NON-NLS-1$
        return imageBlock;
    }

    private static JsonObject buildAnthropicImageBlock(LlmAttachment attachment) {
        EncodedBinary encoded = readBinary(attachment);
        if (encoded == null) {
            return null;
        }
        JsonObject imageBlock = new JsonObject();
        imageBlock.addProperty("type", "image"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject source = new JsonObject();
        source.addProperty("type", "base64"); //$NON-NLS-1$ //$NON-NLS-2$
        source.addProperty("media_type", encoded.mimeType()); //$NON-NLS-1$
        source.addProperty("data", encoded.base64()); //$NON-NLS-1$
        imageBlock.add("source", source); //$NON-NLS-1$
        return imageBlock;
    }

    private static String toDataUri(LlmAttachment attachment) {
        EncodedBinary encoded = readBinary(attachment);
        if (encoded == null) {
            return null;
        }
        return "data:" + encoded.mimeType() + ";base64," + encoded.base64(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static EncodedBinary readBinary(LlmAttachment attachment) {
        if (attachment == null || attachment.getEffectivePath() == null || attachment.getEffectivePath().isBlank()) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(Path.of(attachment.getEffectivePath()));
            String mimeType = attachment.getMimeType();
            if (mimeType == null || mimeType.isBlank()) {
                mimeType = "application/octet-stream"; //$NON-NLS-1$
            }
            return new EncodedBinary(mimeType, Base64.getEncoder().encodeToString(bytes));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private record EncodedBinary(String mimeType, String base64) {
    }
}
