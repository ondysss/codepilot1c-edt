/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IPreferencesService;
import org.eclipse.core.runtime.preferences.IScopeContext;
import org.eclipse.core.runtime.preferences.InstanceScope;

import com.codepilot1c.core.logging.VibeLogger;

/**
 * The line separator a workspace file is written with.
 *
 * <p>Content arrives over MCP with bare LF: JSON carries what the client sent, and models send
 * {@code \n}. Writing that into a CRLF file rewrites every one of its lines - a repository with
 * {@code core.safecrlf=true} then refuses {@code git add} ("LF would be replaced by CRLF"), and a
 * diff shows the whole file as changed. So the separator comes from the file being written, and
 * for a new file - or one that holds no line break of its own - from the EDT preference
 * ({@code Platform.PREF_LINE_SEPARATOR}, looked up per project and then per workspace), the same
 * one the EDT editors use.</p>
 *
 * <p>The one case left alone is a file too long to tell: its first {@value #PROBE_BYTES} bytes
 * hold no line break, so what the rest uses is unknown, and content is written exactly as it
 * came. Guessing there means rewriting a whole file on a hunch, which is what this class exists
 * to prevent.</p>
 */
public final class LineSeparators {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(LineSeparators.class);

    /** Сколько байт файла читать, чтобы найти в нём перенос строк. */
    private static final int PROBE_BYTES = 1_048_576;

    private LineSeparators() {
    }

    /**
     * Returns {@code content} with every line break rewritten as the separator of {@code file}.
     *
     * <p>The writers of module text all need exactly this: the text they build uses {@code "\n"},
     * and the module they write it into is usually CRLF.</p>
     */
    public static String alignTo(IFile file, String content) {
        String separator = ofFile(file);
        return separator == null ? content : normalize(content, separator);
    }

    /**
     * Returns the separator {@code file} should be written with, or {@code null} when that cannot
     * be told and the content is to be written as it came.
     */
    public static String ofFile(IFile file) {
        if (file == null) {
            return null;
        }
        if (!file.exists()) {
            return preferred(file);
        }
        try (InputStream stream = file.getContents(true)) {
            byte[] head = stream.readNBytes(PROBE_BYTES);
            boolean wholeFile = head.length < PROBE_BYTES;
            if (!wholeFile && head.length > 0 && head[head.length - 1] == '\r') {
                // Обрыв пришёлся между CR и LF: одинокий CR тут ничего не значит.
                head = Arrays.copyOf(head, head.length - 1);
            }
            String detected = detect(new String(head, StandardCharsets.UTF_8));
            if (detected != null) {
                return detected;
            }
            return wholeFile ? preferred(file) : null;
        } catch (IOException | CoreException e) {
            LOG.warn("LineSeparators: не удалось прочитать %s: %s", //$NON-NLS-1$
                    file.getFullPath(), e.getMessage());
            return null;
        }
    }

    /**
     * Returns the separator of {@code content}, or the preference of {@code file} when the content
     * is empty or holds no line break.
     */
    public static String of(String content, IFile file) {
        String detected = detect(content);
        return detected != null ? detected : preferred(file);
    }

    /**
     * Returns the separator {@code content} mostly uses, or {@code null} when it has no line
     * break at all.
     *
     * <p>Counting beats looking at the first break: a module where one inserted fragment is LF
     * and the rest CRLF would otherwise be flipped whole by the next write.</p>
     */
    public static String detect(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        int crlfCount = 0;
        int lineFeedCount = 0;
        int carriageReturnCount = 0;
        for (int index = 0; index < content.length(); index++) {
            char symbol = content.charAt(index);
            if (symbol == '\r') {
                if (index + 1 < content.length() && content.charAt(index + 1) == '\n') {
                    crlfCount++;
                    index++;
                } else {
                    carriageReturnCount++;
                }
            } else if (symbol == '\n') {
                lineFeedCount++;
            }
        }
        if (crlfCount == 0 && lineFeedCount == 0 && carriageReturnCount == 0) {
            return null;
        }
        if (crlfCount >= lineFeedCount && crlfCount >= carriageReturnCount) {
            return "\r\n"; //$NON-NLS-1$
        }
        return lineFeedCount >= carriageReturnCount ? "\n" : "\r"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Returns the separator EDT itself would give a new file in this project.
     *
     * <p>Outside a running platform - a headless unit run - there are no preferences to read, and
     * the answer is the one of the machine.</p>
     */
    public static String preferred(IFile file) {
        try {
            IPreferencesService preferences = Platform.getPreferencesService();
            if (preferences == null) {
                return System.lineSeparator();
            }
            IProject project = file == null ? null : file.getProject();
            IScopeContext[] scopes = project != null && project.exists()
                    ? new IScopeContext[] {new ProjectScope(project), InstanceScope.INSTANCE}
                    : new IScopeContext[] {InstanceScope.INSTANCE};
            return preferences.getString(Platform.PI_RUNTIME, Platform.PREF_LINE_SEPARATOR,
                    System.lineSeparator(), scopes);
        } catch (RuntimeException e) {
            return System.lineSeparator();
        }
    }

    /** Rewrites every line break of {@code text} as {@code separator}. */
    public static String normalize(String text, String separator) {
        if (text == null || text.isEmpty() || separator == null || separator.isEmpty()) {
            return text;
        }
        String lineFeeds = text.replace("\r\n", "\n").replace('\r', '\n'); //$NON-NLS-1$ //$NON-NLS-2$
        return "\n".equals(separator) ? lineFeeds : lineFeeds.replace("\n", separator); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
