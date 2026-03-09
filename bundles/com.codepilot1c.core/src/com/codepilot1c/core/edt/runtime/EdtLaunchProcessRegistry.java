package com.codepilot1c.core.edt.runtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks background EDT launch processes and cleans them up on plugin stop.
 */
public final class EdtLaunchProcessRegistry {

    private static final EdtLaunchProcessRegistry INSTANCE = new EdtLaunchProcessRegistry();

    private final Map<String, Process> processes = new ConcurrentHashMap<>();

    private EdtLaunchProcessRegistry() {
    }

    public static EdtLaunchProcessRegistry getInstance() {
        return INSTANCE;
    }

    public void register(String opId, Process process) {
        if (opId == null || opId.isBlank() || process == null) {
            return;
        }
        processes.put(opId, process);
        process.onExit().whenComplete((ignored, throwable) -> processes.remove(opId, process));
    }

    public Process get(String opId) {
        return opId == null ? null : processes.get(opId);
    }

    public void cleanupAll() {
        for (Map.Entry<String, Process> entry : processes.entrySet()) {
            Process process = entry.getValue();
            if (process == null) {
                continue;
            }
            try {
                process.getInputStream().close();
            } catch (Exception e) {
                // Ignore cleanup failures.
            }
            try {
                process.getErrorStream().close();
            } catch (Exception e) {
                // Ignore cleanup failures.
            }
            try {
                process.getOutputStream().close();
            } catch (Exception e) {
                // Ignore cleanup failures.
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        processes.clear();
    }
}
