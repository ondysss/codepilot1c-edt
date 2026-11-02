package com.codepilot1c.core.edt.metadata;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Validates the complete help request before either BM or workspace files are touched. */
final class HelpPagePatch {
    private HelpPagePatch() {}

    static Map<String, String> fromChanges(Map<String, Object> changes) {
        Object rawSet = changes.get("set");
        if (!(rawSet instanceof Map<?, ?> set) || !set.containsKey("help")) {
            return null;
        }
        if (changes.size() != 1 || set.size() != 1) {
            throw invalid("Help content must be updated in a separate request: set.help only");
        }
        Object raw = set.get("help");
        if (!(raw instanceof Map<?, ?> pages) || pages.isEmpty()) {
            throw invalid("set.help requires a nonempty language-to-HTML object");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : pages.entrySet()) {
            if (!(entry.getKey() instanceof String language)
                    || !language.matches("[a-zA-Z]{2,3}(?:[-_][a-zA-Z0-9]{2,8})?")) {
                throw invalid("Help language must be a language code, not a file path");
            }
            String normalized = language.toLowerCase(Locale.ROOT);
            if (!(entry.getValue() instanceof String html) || html.isBlank() || html.indexOf('\0') >= 0) {
                throw invalid("Help HTML must be a nonempty string without NUL");
            }
            if (result.putIfAbsent(normalized, html) != null) {
                throw invalid("Duplicate help language: " + normalized);
            }
        }
        return result;
    }

    private static MetadataOperationException invalid(String message) {
        return new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_CHANGE, message, false);
    }
}
