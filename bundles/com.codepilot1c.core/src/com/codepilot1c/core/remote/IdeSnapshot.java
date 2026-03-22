package com.codepilot1c.core.remote;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Snapshot of current IDE/workbench state for remote UI.
 */
public class IdeSnapshot {

    private final Instant capturedAt;
    private final Map<String, Object> workbench;
    private final Map<String, Object> editor;
    private final List<Map<String, Object>> diagnostics;
    private final List<Map<String, Object>> openViews;
    private final List<Map<String, Object>> commands;

    public IdeSnapshot(
            Instant capturedAt,
            Map<String, Object> workbench,
            Map<String, Object> editor,
            List<Map<String, Object>> diagnostics,
            List<Map<String, Object>> openViews,
            List<Map<String, Object>> commands) {
        this.capturedAt = capturedAt != null ? capturedAt : Instant.now();
        this.workbench = copyMap(workbench);
        this.editor = copyMap(editor);
        this.diagnostics = diagnostics != null ? List.copyOf(diagnostics) : List.of();
        this.openViews = openViews != null ? List.copyOf(openViews) : List.of();
        this.commands = commands != null ? List.copyOf(commands) : List.of();
    }

    public static IdeSnapshot unavailable(String reason) {
        return new IdeSnapshot(
                Instant.now(),
                Map.of("available", Boolean.FALSE, "reason", reason != null ? reason : "Мост workbench недоступен"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                Map.of(),
                List.of(),
                List.of(),
                List.of());
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public Map<String, Object> getWorkbench() {
        return workbench;
    }

    public Map<String, Object> getEditor() {
        return editor;
    }

    public List<Map<String, Object>> getDiagnostics() {
        return diagnostics;
    }

    public List<Map<String, Object>> getOpenViews() {
        return openViews;
    }

    public List<Map<String, Object>> getCommands() {
        return commands;
    }

    private static Map<String, Object> copyMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
}
