package com.codepilot1c.core.tools;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Parses common tool error formats into a normalized structure.
 */
public final class ToolErrorParser {

    private static final Pattern BRACKET_ERROR = Pattern.compile("^\\[([A-Z0-9_-]+)]\\s*(.*)$"); //$NON-NLS-1$
    private static final Pattern COLON_ERROR = Pattern.compile("^([A-Z][A-Z0-9_-]+):\\s*(.+)$"); //$NON-NLS-1$
    private static final String LLM_ERROR_PREFIX = "Error:"; //$NON-NLS-1$

    private ToolErrorParser() {
        // Utility class.
    }

    public static ParsedToolError parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ParsedToolError(raw, null, null, null, null, null, null, null, null, "empty"); //$NON-NLS-1$
        }

        String normalized = stripLlmPrefix(raw.trim());
        if (normalized.startsWith("{")) { //$NON-NLS-1$
            ParsedToolError parsed = parseJson(normalized);
            if (parsed != null) {
                return parsed;
            }
        }

        Matcher bracketMatcher = BRACKET_ERROR.matcher(normalized);
        if (bracketMatcher.matches()) {
            return new ParsedToolError(
                    normalized,
                    bracketMatcher.group(1),
                    emptyToNull(bracketMatcher.group(2)),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "bracket"); //$NON-NLS-1$
        }

        Matcher colonMatcher = COLON_ERROR.matcher(normalized);
        if (colonMatcher.matches()) {
            return new ParsedToolError(
                    normalized,
                    colonMatcher.group(1),
                    emptyToNull(colonMatcher.group(2)),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "colon"); //$NON-NLS-1$
        }

        return new ParsedToolError(normalized, null, normalized, null, null, null, null, null, null, "plain"); //$NON-NLS-1$
    }

    private static ParsedToolError parseJson(String raw) {
        try {
            JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
            String errorCode = firstNonBlank(getString(obj, "error_code"), getString(obj, "error")); //$NON-NLS-1$ //$NON-NLS-2$
            String format = obj.has("error_code") ? "json_error_code" : obj.has("error") ? "json_error" : "json"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return new ParsedToolError(
                    raw,
                    errorCode,
                    firstNonBlank(getString(obj, "message"), raw), //$NON-NLS-1$
                    getString(obj, "log_path"), //$NON-NLS-1$
                    getString(obj, "tail_log"), //$NON-NLS-1$
                    getString(obj, "status"), //$NON-NLS-1$
                    getString(obj, "op_id"), //$NON-NLS-1$
                    getString(obj, "project_name"), //$NON-NLS-1$
                    obj.has("recoverable") ? Boolean.valueOf(obj.get("recoverable").getAsBoolean()) : null, //$NON-NLS-1$
                    format);
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripLlmPrefix(String raw) {
        if (raw.regionMatches(true, 0, LLM_ERROR_PREFIX, 0, LLM_ERROR_PREFIX.length())) {
            return raw.substring(LLM_ERROR_PREFIX.length()).trim();
        }
        return raw;
    }

    private static String getString(JsonObject object, String name) {
        if (object == null || name == null || !object.has(name) || object.get(name).isJsonNull()) {
            return null;
        }
        try {
            String value = object.get(name).getAsString();
            return emptyToNull(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstNonBlank(String first, String second) {
        return emptyToNull(first) != null ? first : emptyToNull(second);
    }

    private static String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record ParsedToolError(
            String raw,
            String errorCode,
            String message,
            String logPath,
            String embeddedLogTail,
            String status,
            String opId,
            String projectName,
            Boolean recoverable,
            String format) {
    }
}
