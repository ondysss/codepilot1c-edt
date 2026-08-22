/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.gsd;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.codepilot1c.core.gsd.GsdConcurrencyToken;
import com.codepilot1c.core.gsd.GsdState;
import com.codepilot1c.core.gsd.GsdWorkflowService;
import com.codepilot1c.core.filesystem.SecureDirectoryCapabilityException;
import com.codepilot1c.core.tools.ToolExecutionContext;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolParameters.ToolParameterException;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.util.ThrowableCauseTraversal;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Shared fail-closed execution identity and schema-v2 token helpers for GSD tools. */
final class GsdToolSupport {

    private GsdToolSupport() {
    }

    static void requireOnly(ToolParameters params, String... allowedNames) {
        Set<String> allowed = Set.of(allowedNames);
        params.getRaw().keySet().stream()
                .filter(name -> !allowed.contains(name))
                .sorted()
                .findFirst()
                .ifPresent(name -> {
                    throw new ToolParameterException(
                            "Unexpected parameter '" + name + "'"); //$NON-NLS-1$ //$NON-NLS-2$
                });
    }

    static String optionalString(ToolParameters params, String name) {
        Object value = params.getRaw().get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new ToolParameterException("Parameter '" + name + "' must be a string"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return text;
    }

    static List<String> optionalStringList(ToolParameters params, String name) {
        Object value = params.getRaw().get(name);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> values)) {
            throw new ToolParameterException("Parameter '" + name + "' must be an array"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        List<String> result = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            Object item = values.get(i);
            if (!(item instanceof String text)) {
                throw new ToolParameterException(
                        "Parameter '" + name + "[" + i + "]' must be a string"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    static String requireProject(ToolParameters params, ToolExecutionContext context) {
        String requested = params.requireString("project_path"); //$NON-NLS-1$
        if (context == null || !context.isScoped()
                || context.projectPath().isBlank() || context.sessionId().isBlank()) {
            throw new GsdToolIdentityException(
                    "GSD execution requires scoped project_path and session identity"); //$NON-NLS-1$
        }
        try {
            Path requestedPath = Path.of(requested);
            Path capturedPath = Path.of(context.projectPath());
            if (!requestedPath.isAbsolute() || !capturedPath.isAbsolute()) {
                throw new GsdToolIdentityException(
                        "GSD execution requires an absolute project_path identity"); //$NON-NLS-1$
            }
            requestedPath = requestedPath.normalize();
            capturedPath = capturedPath.normalize();
            if (!requestedPath.equals(capturedPath)) {
                throw new GsdToolIdentityException(
                        "project_path does not match the captured execution identity"); //$NON-NLS-1$
            }
            return requestedPath.toString();
        } catch (InvalidPathException e) {
            throw new GsdToolIdentityException("project_path is invalid"); //$NON-NLS-1$
        }
    }

    static GsdConcurrencyToken requireToken(ToolParameters params) {
        return new GsdConcurrencyToken(
                params.requireString("expected_cycle_id"), //$NON-NLS-1$
                requireJsonInteger(params, "expected_generation"), //$NON-NLS-1$
                requireJsonInteger(params, "expected_revision")); //$NON-NLS-1$
    }

    /**
     * Reads an integer-schema value without accepting a numeric string. Tool schemas
     * describe token numbers as JSON integers, so runtime parsing must preserve that
     * type contract instead of applying compatibility coercion from {@link ToolParameters}.
     */
    private static long requireJsonInteger(ToolParameters params, String name) {
        Object value = params.getRaw().get(name);
        if (!(value instanceof Number)) {
            String actual = value == null ? "missing" : value.getClass().getSimpleName(); //$NON-NLS-1$
            throw new ToolParameterException(
                    "Parameter '" + name + "' must be a JSON integer, got " + actual); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return params.requireLong(name);
    }

    static JsonObject stateEnvelope(String operation, GsdState state) {
        JsonObject result = GsdWorkflowService.buildResult(
                true, operation, state.revision(), state.phase(), null);
        result.addProperty("cycle_id", state.cycleId()); //$NON-NLS-1$
        result.addProperty("generation", state.generation()); //$NON-NLS-1$
        return result;
    }

    static ToolResult identityFailure(String operation, GsdToolIdentityException e) {
        return ToolResult.failure(e.getMessage(), GsdWorkflowService.buildResult(
                false, operation, 0, null, GsdWorkflowService.ERR_IDENTITY));
    }

    static ToolResult failure(String operation, String code, String message) {
        return ToolResult.failure(message, GsdWorkflowService.buildResult(
                false, operation, 0, null, code));
    }

    static ToolResult ioFailure(String operation, String prefix, IOException failure) {
        String code = hasCapabilityCause(failure)
                ? GsdWorkflowService.ERR_UNSUPPORTED : GsdWorkflowService.ERR_IO;
        String message = (prefix == null ? "" : prefix) + failure.getMessage(); //$NON-NLS-1$
        return failure(operation, code, message);
    }

    private static boolean hasCapabilityCause(Throwable failure) {
        return ThrowableCauseTraversal.contains(
                failure, SecureDirectoryCapabilityException.class);
    }

    static void addWarnings(JsonObject payload, java.util.List<String> warnings) {
        JsonArray array = new JsonArray();
        if (warnings != null) {
            warnings.stream().sorted().forEach(array::add);
        }
        payload.add("warnings", array); //$NON-NLS-1$
    }

    static final class GsdToolIdentityException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        GsdToolIdentityException(String message) {
            super(message);
        }
    }
}
