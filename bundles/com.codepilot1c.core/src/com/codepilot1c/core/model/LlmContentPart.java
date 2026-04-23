/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.model;

import java.util.Objects;

/**
 * Structured content part used by multimodal chat messages.
 */
public class LlmContentPart {

    public enum Type {
        TEXT,
        IMAGE,
        FILE
    }

    private final Type type;
    private final String text;
    private final LlmAttachment attachment;

    private LlmContentPart(Type type, String text, LlmAttachment attachment) {
        this.type = Objects.requireNonNull(type, "type"); //$NON-NLS-1$
        this.text = text;
        this.attachment = attachment;
    }

    public static LlmContentPart text(String text) {
        return new LlmContentPart(Type.TEXT, text != null ? text : "", null); //$NON-NLS-1$
    }

    public static LlmContentPart image(LlmAttachment attachment) {
        return new LlmContentPart(Type.IMAGE, null, Objects.requireNonNull(attachment, "attachment")); //$NON-NLS-1$
    }

    public static LlmContentPart file(LlmAttachment attachment) {
        return new LlmContentPart(Type.FILE, null, Objects.requireNonNull(attachment, "attachment")); //$NON-NLS-1$
    }

    public Type getType() {
        return type;
    }

    public String getText() {
        return text;
    }

    public LlmAttachment getAttachment() {
        return attachment;
    }

    public boolean isText() {
        return type == Type.TEXT;
    }

    public boolean isImage() {
        return type == Type.IMAGE;
    }

    public boolean isFile() {
        return type == Type.FILE;
    }

    public String toTextFallback() {
        if (isText()) {
            return text != null ? text : ""; //$NON-NLS-1$
        }
        if (attachment == null) {
            return ""; //$NON-NLS-1$
        }
        if (isImage()) {
            return "[Image: " + attachment.toDisplayLabel() + "]"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[File: ").append(attachment.toDisplayLabel()).append(']'); //$NON-NLS-1$
        if (attachment.getPreviewText() != null && !attachment.getPreviewText().isBlank()) {
            sb.append("\n").append(attachment.getPreviewText()); //$NON-NLS-1$
        }
        return sb.toString();
    }
}
