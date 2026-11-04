/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.file;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.codepilot1c.core.edit.LineSeparators;
import com.google.gson.JsonArray;

final class WorkspaceCopyTransformSupport {

    private static final int CHANGED_LINE_LIMIT = 8;

    private WorkspaceCopyTransformSupport() {
    }

    static Validation validateWorkspacePath(String rawPath, boolean targetPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return Validation.error("PATH_REQUIRED", "Path must not be blank"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (rawPath.indexOf('\0') >= 0) {
            return Validation.error("INVALID_PATH", "Path contains a NUL character"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String normalized = rawPath.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.startsWith("//") //$NON-NLS-1$ //$NON-NLS-2$
                || normalized.matches("^[A-Za-z]:/.*")) { //$NON-NLS-1$
            return Validation.error("PATH_OUTSIDE_WORKSPACE", //$NON-NLS-1$
                    "Path must be workspace-relative: " + rawPath); //$NON-NLS-1$
        }
        String[] segments = normalized.split("/", -1); //$NON-NLS-1$
        if (segments.length == 0) {
            return Validation.error("PATH_REQUIRED", "Path must include a project segment"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (String segment : segments) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) { //$NON-NLS-1$ //$NON-NLS-2$
                return Validation.error("PATH_OUTSIDE_WORKSPACE", //$NON-NLS-1$
                        "Path traversal and empty segments are not allowed: " + rawPath); //$NON-NLS-1$
            }
        }
        if (targetPath && isBlockedStructuredEdtArtifact(normalized)) {
            return Validation.error("STRUCTURED_EDT_ARTIFACT_BLOCKED", //$NON-NLS-1$
                    "Direct writes to .mdo/.form/.mxl/DCS artifacts are blocked: " + rawPath); //$NON-NLS-1$
        }
        if (targetPath && !hasAllowedTextExtension(normalized)) {
            return Validation.error("DISALLOWED_TARGET_EXTENSION", //$NON-NLS-1$
                    "Target extension is not allowed for this tool: " + rawPath); //$NON-NLS-1$
        }
        return Validation.ok(normalized);
    }

    static boolean isBlockedStructuredEdtArtifact(String normalizedPath) {
        if (normalizedPath == null) {
            return false;
        }
        String lower = normalizedPath.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mdo") //$NON-NLS-1$
                || lower.endsWith(".form") //$NON-NLS-1$
                || lower.endsWith(".form.xml") //$NON-NLS-1$
                || lower.endsWith(".mxl") //$NON-NLS-1$
                || lower.endsWith(".dcs") //$NON-NLS-1$
                || lower.endsWith("/main@datacompositionschema.xml") //$NON-NLS-1$
                || lower.endsWith("/maindatacompositionschema.xml") //$NON-NLS-1$
                || lower.contains("/ext/maindatacompositionschema."); //$NON-NLS-1$
    }

    static boolean hasAllowedTextExtension(String normalizedPath) {
        if (normalizedPath == null) {
            return false;
        }
        String lower = normalizedPath.toLowerCase(Locale.ROOT);
        return lower.endsWith(".bsl") //$NON-NLS-1$
                || lower.endsWith(".os") //$NON-NLS-1$
                || lower.endsWith(".txt") //$NON-NLS-1$
                || lower.endsWith(".md"); //$NON-NLS-1$
    }

    static Charset resolveCharset(String encoding) {
        if (encoding == null || encoding.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        return Charset.forName(encoding);
    }

    static TransformResult transform(String content, List<PlainReplacement> plainReplacements,
            List<RegexReplacement> regexReplacements, boolean preserveEol) {
        String transformed = content != null ? content : ""; //$NON-NLS-1$
        String lineSeparator = preserveEol ? detectLineSeparator(transformed) : "\n"; //$NON-NLS-1$
        List<ReplacementCount> counts = new ArrayList<>();

        for (PlainReplacement replacement : plainReplacements) {
            String from = requireNonEmpty(replacement.from(), "replacement.from"); //$NON-NLS-1$
            String to = preserveEol ? normalizeLineEndings(replacement.to(), lineSeparator) : replacement.to();
            ApplyResult applied = applyPlain(transformed, from, to != null ? to : ""); //$NON-NLS-1$
            transformed = applied.content();
            counts.add(new ReplacementCount("plain", from, to, applied.count())); //$NON-NLS-1$
        }

        for (RegexReplacement replacement : regexReplacements) {
            String pattern = requireNonEmpty(replacement.pattern(), "regex_replacement.pattern"); //$NON-NLS-1$
            String to = preserveEol
                    ? normalizeLineEndings(replacement.replacement(), lineSeparator)
                    : replacement.replacement();
            ApplyResult applied = applyRegex(transformed, pattern, to != null ? to : ""); //$NON-NLS-1$
            transformed = applied.content();
            counts.add(new ReplacementCount("regex", pattern, to, applied.count())); //$NON-NLS-1$
        }

        return new TransformResult(transformed, counts, changedLines(content != null ? content : "", transformed)); //$NON-NLS-1$
    }

    static List<PlainReplacement> parsePlainReplacements(Object raw) {
        List<PlainReplacement> result = new ArrayList<>();
        for (Map<String, Object> item : parseObjectList(raw, "replacements")) { //$NON-NLS-1$
            result.add(new PlainReplacement(stringValue(item.get("from")), stringValue(item.get("to")))); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return result;
    }

    static List<RegexReplacement> parseRegexReplacements(Object raw) {
        List<RegexReplacement> result = new ArrayList<>();
        for (Map<String, Object> item : parseObjectList(raw, "regex_replacements")) { //$NON-NLS-1$
            result.add(new RegexReplacement(stringValue(item.get("pattern")), //$NON-NLS-1$
                    stringValue(item.get("replacement")))); //$NON-NLS-1$
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> parseObjectList(Object raw, String fieldName) {
        if (raw == null) {
            return List.of();
        }
        Object value = raw;
        if (raw instanceof String str) {
            if (str.isBlank()) {
                return List.of();
            }
            value = new com.codepilot1c.core.tools.ToolArgumentParser()
                    .parseArguments("{\"items\":" + str + "}") //$NON-NLS-1$ //$NON-NLS-2$
                    .get("items"); //$NON-NLS-1$
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(fieldName + " must be an array"); //$NON-NLS-1$
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException(fieldName + " items must be objects"); //$NON-NLS-1$
            }
            result.add((Map<String, Object>) map);
        }
        return result;
    }

    static JsonArray replacementCountsJson(List<ReplacementCount> counts) {
        JsonArray array = new JsonArray();
        for (ReplacementCount count : counts) {
            var obj = new com.google.gson.JsonObject();
            obj.addProperty("type", count.type()); //$NON-NLS-1$
            if ("regex".equals(count.type())) { //$NON-NLS-1$
                obj.addProperty("pattern", count.from()); //$NON-NLS-1$
                obj.addProperty("replacement", count.to()); //$NON-NLS-1$
            } else {
                obj.addProperty("from", count.from()); //$NON-NLS-1$
                obj.addProperty("to", count.to()); //$NON-NLS-1$
            }
            obj.addProperty("count", count.count()); //$NON-NLS-1$
            array.add(obj);
        }
        return array;
    }

    static JsonArray changedLinesJson(List<ChangedLine> lines) {
        JsonArray array = new JsonArray();
        for (ChangedLine line : lines) {
            var obj = new com.google.gson.JsonObject();
            obj.addProperty("line", line.line()); //$NON-NLS-1$
            obj.addProperty("before", line.before()); //$NON-NLS-1$
            obj.addProperty("after", line.after()); //$NON-NLS-1$
            array.add(obj);
        }
        return array;
    }

    static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
            return HexFormat.of().formatHex(digest.digest(bytes != null ? bytes : new byte[0]));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e); //$NON-NLS-1$
        }
    }

    static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String requireNonEmpty(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty"); //$NON-NLS-1$
        }
        return value;
    }

    private static ApplyResult applyPlain(String content, String from, String to) {
        int index = 0;
        int count = 0;
        StringBuilder sb = new StringBuilder(content.length());
        while (true) {
            int found = content.indexOf(from, index);
            if (found < 0) {
                sb.append(content, index, content.length());
                break;
            }
            sb.append(content, index, found);
            sb.append(to);
            index = found + from.length();
            count++;
        }
        return new ApplyResult(sb.toString(), count);
    }

    private static ApplyResult applyRegex(String content, String patternText, String replacement) {
        try {
            Pattern pattern = Pattern.compile(patternText);
            Matcher countMatcher = pattern.matcher(content);
            int count = 0;
            while (countMatcher.find()) {
                count++;
            }
            Matcher replaceMatcher = pattern.matcher(content);
            return new ApplyResult(replaceMatcher.replaceAll(replacement), count);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid regex replacement '" + patternText + "': " //$NON-NLS-1$ //$NON-NLS-2$
                    + e.getMessage(), e);
        }
    }

    private static List<ChangedLine> changedLines(String before, String after) {
        String[] beforeLines = before.split("\\R", -1); //$NON-NLS-1$
        String[] afterLines = after.split("\\R", -1); //$NON-NLS-1$
        int max = Math.max(beforeLines.length, afterLines.length);
        List<ChangedLine> result = new ArrayList<>();
        for (int i = 0; i < max && result.size() < CHANGED_LINE_LIMIT; i++) {
            String beforeLine = i < beforeLines.length ? beforeLines[i] : ""; //$NON-NLS-1$
            String afterLine = i < afterLines.length ? afterLines[i] : ""; //$NON-NLS-1$
            if (!beforeLine.equals(afterLine)) {
                result.add(new ChangedLine(i + 1, beforeLine, afterLine));
            }
        }
        return result;
    }

    /** Источник без переносов ничего не диктует - остаётся перенос машины, как и до правки. */
    private static String detectLineSeparator(String content) {
        String detected = LineSeparators.detect(content);
        return detected != null ? detected : System.lineSeparator();
    }

    private static String normalizeLineEndings(String text, String lineSeparator) {
        return LineSeparators.normalize(text, lineSeparator);
    }

    record PlainReplacement(String from, String to) {
    }

    record RegexReplacement(String pattern, String replacement) {
    }

    record ReplacementCount(String type, String from, String to, int count) {
    }

    record ChangedLine(int line, String before, String after) {
    }

    record TransformResult(String content, List<ReplacementCount> replacementCounts, List<ChangedLine> changedLines) {
    }

    record Validation(boolean ok, String normalizedPath, String errorCode, String message) {
        static Validation ok(String normalizedPath) {
            return new Validation(true, normalizedPath, null, null);
        }

        static Validation error(String errorCode, String message) {
            return new Validation(false, null, errorCode, message);
        }
    }

    private record ApplyResult(String content, int count) {
    }
}
