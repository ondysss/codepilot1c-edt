/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.gsd;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.gsd.GsdContentRejectedException;
import com.codepilot1c.core.gsd.GsdAcceptanceCriterion;
import com.codepilot1c.core.gsd.GsdExecutionKind;
import com.codepilot1c.core.gsd.GsdState;
import com.codepilot1c.core.gsd.GsdTask;
import com.codepilot1c.core.gsd.GsdTaskStatus;
import com.codepilot1c.core.gsd.GsdWave;
import com.codepilot1c.core.gsd.GsdWorkflowService;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolExecutionContext;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolParameters.ToolParameterException;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;

/**
 * Creates a plan: sets the goal, tasks, and waves. Does <em>not</em> change the phase;
 * phase transitions are performed exclusively by {@code gsd_transition}.
 * Allowed in PLANNING phase only.
 *
 * <p>Schema requires {@code project_path}, {@code expected_revision}, {@code goal},
 * {@code tasks} (array of objects with {@code id}, {@code title}, optionally {@code status},
 * {@code wave_id}, {@code depends_on}), and {@code waves} (array with {@code id}, {@code name},
 * optionally {@code goal}, {@code task_ids}).</p>
 */
@ToolMeta(
    name = "gsd_create_plan",
    category = "gsd",
    mutating = true,
    tags = {"gsd", "planning"}
)
public class GsdCreatePlanTool extends AbstractTool {

    static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project_path": {
                  "type": "string",
                  "description": "Absolute path to the project root."
                },
                "expected_cycle_id": {"type": "string"},
                "expected_generation": {"type": "integer"},
                "expected_revision": {
                  "type": "integer",
                  "description": "Expected revision for optimistic concurrency."
                },
                "goal": {
                  "type": "string",
                  "description": "The project goal statement."
                },
                "acceptance_criteria": {
                  "type": "array",
                  "minItems": 1,
                  "description": "Acceptance criteria; at least one item must have required=true.",
                  "items": {
                    "type": "object",
                    "properties": {
                      "id": {"type": "string"},
                      "description": {"type": "string"},
                      "required": {"type": "boolean"}
                    },
                    "required": ["id", "description", "required"],
                    "additionalProperties": false
                  }
                },
                "tasks": {
                  "type": "array",
                  "description": "Work items to plan.",
                  "items": {
                    "type": "object",
                    "properties": {
                      "id": {"type": "string"},
                      "title": {"type": "string"},
                      "execution_kind": {"type": "string", "enum": ["READ_ONLY", "FILE_MUTATION", "EDT_MUTATION", "GIT_MUTATION"]},
                      "wave_id": {"type": "string"},
                      "depends_on": {"type": "array", "items": {"type": "string"}}
                    },
                    "required": ["id", "title", "execution_kind"],
                    "additionalProperties": false
                  }
                },
                "waves": {
                  "type": "array",
                  "description": "Ordered waves of tasks.",
                  "items": {
                    "type": "object",
                    "properties": {
                      "id": {"type": "string"},
                      "name": {"type": "string"},
                      "goal": {"type": "string"},
                      "task_ids": {"type": "array", "items": {"type": "string"}}
                    },
                    "required": ["id", "name"],
                    "additionalProperties": false
                  }
                }
              },
              "required": ["project_path", "expected_cycle_id", "expected_generation", "expected_revision", "goal", "acceptance_criteria", "tasks", "waves"],
              "additionalProperties": false
            }
            """; //$NON-NLS-1$

    @Override
    public String getDescription() {
        return "Creates a GSD plan by setting the goal, acceptance criteria, tasks, and waves. " //$NON-NLS-1$
                + "Does not change the phase; use gsd_transition for that.";
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return doExecute(params, ToolExecutionContext.unscoped());
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(
            ToolParameters params, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeInternal(params, context);
            } catch (GsdToolSupport.GsdToolIdentityException e) {
                return GsdToolSupport.identityFailure("gsd_create_plan", e); //$NON-NLS-1$
            } catch (ToolParameterException e) {
                return ToolResult.failure("Parameter error: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_create_plan", 0, null, GsdWorkflowService.ERR_INVALID)); //$NON-NLS-1$
            } catch (IllegalArgumentException e) {
                return ToolResult.failure("Invalid input: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_create_plan", 0, null, GsdWorkflowService.ERR_INVALID)); //$NON-NLS-1$
            } catch (IllegalStateException e) {
                return ToolResult.failure("Illegal state: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_create_plan", 0, null, GsdWorkflowService.ERR_INVALID)); //$NON-NLS-1$
            } catch (GsdContentRejectedException e) {
                return ToolResult.failure("Content rejected: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_create_plan", 0, null, GsdWorkflowService.ERR_SECURITY)); //$NON-NLS-1$
            } catch (com.codepilot1c.core.gsd.GsdStaleTokenException e) {
                return ToolResult.failure("Stale concurrency token", //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_create_plan", 0, null, GsdWorkflowService.ERR_STALE)); //$NON-NLS-1$
            } catch (com.codepilot1c.core.gsd.GsdStaleRevisionException e) {
                return ToolResult.failure("Stale revision: expected " + e.getExpectedRevision() //$NON-NLS-1$
                                + ", current " + e.getActualRevision(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_create_plan", 0, null, GsdWorkflowService.ERR_STALE)); //$NON-NLS-1$
            } catch (com.codepilot1c.core.gsd.GsdGuardException e) {
                return ToolResult.failure("Guard violation: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_create_plan", 0, null, GsdWorkflowService.ERR_GUARD)); //$NON-NLS-1$
            } catch (com.codepilot1c.core.gsd.GsdCorruptException e) {
                return ToolResult.failure("GSD state is corrupt: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_create_plan", 0, null, GsdWorkflowService.ERR_CORRUPT)); //$NON-NLS-1$
            } catch (IOException e) {
                return ToolResult.failure("I/O error: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_create_plan", 0, null, GsdWorkflowService.ERR_IO)); //$NON-NLS-1$
            } catch (RuntimeException e) {
                return ToolResult.failure("Internal error: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_create_plan", 0, null, GsdWorkflowService.ERR_INVALID)); //$NON-NLS-1$
            }
        });
    }

    private ToolResult executeInternal(ToolParameters params, ToolExecutionContext context)
            throws IOException {
        GsdToolSupport.requireOnly(params, "project_path", "expected_cycle_id", //$NON-NLS-1$ //$NON-NLS-2$
                "expected_generation", "expected_revision", "goal", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "acceptance_criteria", "tasks", "waves"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String projectPath = GsdToolSupport.requireProject(params, context);
        var expectedToken = GsdToolSupport.requireToken(params);
        String goal = params.requireString("goal"); //$NON-NLS-1$

        Object rawCriteriaObj = params.getRaw().get("acceptance_criteria"); //$NON-NLS-1$
        Object rawTasksObj = params.getRaw().get("tasks"); //$NON-NLS-1$
        Object rawWavesObj = params.getRaw().get("waves"); //$NON-NLS-1$

        if (!(rawCriteriaObj instanceof List<?>)) {
            throw new IllegalArgumentException("'acceptance_criteria' must be an array"); //$NON-NLS-1$
        }
        if (!(rawTasksObj instanceof List<?>)) {
            throw new IllegalArgumentException("'tasks' must be an array"); //$NON-NLS-1$
        }
        if (!(rawWavesObj instanceof List<?>)) {
            throw new IllegalArgumentException("'waves' must be an array"); //$NON-NLS-1$
        }

        List<?> rawCriteria = (List<?>) rawCriteriaObj;
        List<?> rawTasks = (List<?>) rawTasksObj;
        List<?> rawWaves = (List<?>) rawWavesObj;

        if (rawCriteria.isEmpty()) {
            throw new IllegalArgumentException("acceptance_criteria must not be empty"); //$NON-NLS-1$
        }
        if (rawTasks.isEmpty()) {
            throw new IllegalArgumentException("tasks must not be empty"); //$NON-NLS-1$
        }
        if (rawWaves.isEmpty()) {
            throw new IllegalArgumentException("waves must not be empty"); //$NON-NLS-1$
        }

        List<GsdAcceptanceCriterion> criteria = parseCriteria(rawCriteria);
        if (criteria.stream().noneMatch(GsdAcceptanceCriterion::required)) {
            throw new IllegalArgumentException(
                    "acceptance_criteria must contain at least one required criterion"); //$NON-NLS-1$
        }
        List<GsdTask> tasks = parseTasks(rawTasks);
        List<GsdWave> waves = parseWaves(rawWaves);

        GsdState state = GsdWorkflowService.createPlan(
                projectPath, expectedToken, goal, criteria, tasks, waves);
        JsonObject structured = GsdToolSupport.stateEnvelope("gsd_create_plan", state); //$NON-NLS-1$
        return ToolResult.success(
                "Plan created. Phase: " + state.phase() + ", Revision: " + state.revision(), //$NON-NLS-1$ //$NON-NLS-2$
                structured);
    }

    private static final Set<String> ALLOWED_TASK_KEYS = Set.of(
            "id", "title", "execution_kind", "wave_id", "depends_on"); //$NON-NLS-1$
    private static final Set<String> ALLOWED_WAVE_KEYS = Set.of(
            "id", "name", "goal", "task_ids"); //$NON-NLS-1$
    private static final Set<String> ALLOWED_CRITERION_KEYS = Set.of(
            "id", "description", "required"); //$NON-NLS-1$

    @SuppressWarnings("unchecked")
    private List<GsdAcceptanceCriterion> parseCriteria(List<?> rawCriteria) {
        List<GsdAcceptanceCriterion> criteria = new ArrayList<>();
        for (int i = 0; i < rawCriteria.size(); i++) {
            Object raw = rawCriteria.get(i);
            if (!(raw instanceof Map<?, ?>)) {
                throw new IllegalArgumentException(
                        "acceptance_criteria[" + i + "] is not an object"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            Map<String, Object> values = (Map<String, Object>) raw;
            for (String key : values.keySet()) {
                if (!ALLOWED_CRITERION_KEYS.contains(key)) {
                    throw new IllegalArgumentException(
                            "acceptance_criteria[" + i + "] has unexpected key '" + key + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
            }
            Object id = values.get("id"); //$NON-NLS-1$
            Object description = values.get("description"); //$NON-NLS-1$
            Object required = values.get("required"); //$NON-NLS-1$
            if (!(id instanceof String idText) || idText.isBlank()) {
                throw new IllegalArgumentException(
                        "acceptance_criteria[" + i + "].id must be a non-blank string"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (!(description instanceof String descriptionText)
                    || descriptionText.isBlank()) {
                throw new IllegalArgumentException(
                        "acceptance_criteria[" + i + "].description must be a non-blank string"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (!(required instanceof Boolean)) {
                throw new IllegalArgumentException(
                        "acceptance_criteria[" + i + "].required must be boolean"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            criteria.add(new GsdAcceptanceCriterion(
                    (String) id, (String) description, (Boolean) required));
        }
        return criteria;
    }

    @SuppressWarnings("unchecked")
    private List<GsdTask> parseTasks(List<?> rawTasks) {
        List<GsdTask> tasks = new ArrayList<>();
        for (int i = 0; i < rawTasks.size(); i++) {
            Object raw = rawTasks.get(i);
            if (!(raw instanceof Map)) {
                throw new IllegalArgumentException("tasks[" + i + "] is not an object"); //$NON-NLS-1$
            }
            Map<String, Object> m = (Map<String, Object>) raw;
            // Reject any key outside the allowlist.
            for (String key : m.keySet()) {
                if (!ALLOWED_TASK_KEYS.contains(key)) {
                    throw new IllegalArgumentException(
                            "tasks[" + i + "] has unexpected key '" + key + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
            }

            Object idObj = m.get("id"); //$NON-NLS-1$
            if (!(idObj instanceof String) || ((String) idObj).isBlank()) {
                throw new IllegalArgumentException("tasks[" + i + "].id must be a non-blank string"); //$NON-NLS-1$
            }
            String id = (String) idObj;

            Object titleObj = m.get("title"); //$NON-NLS-1$
            if (!(titleObj instanceof String) || ((String) titleObj).isBlank()) {
                throw new IllegalArgumentException("tasks[" + i + "].title must be a non-blank string"); //$NON-NLS-1$
            }
            String title = (String) titleObj;

            Object ekObj = m.get("execution_kind"); //$NON-NLS-1$
            if (!(ekObj instanceof String) || ((String) ekObj).isBlank()) {
                throw new IllegalArgumentException("tasks[" + i + "].execution_kind is required"); //$NON-NLS-1$
            }
            GsdExecutionKind executionKind = GsdExecutionKind.fromName((String) ekObj);
            if (executionKind == null) {
                throw new IllegalArgumentException("tasks[" + i + "].execution_kind: unknown '" + ekObj //$NON-NLS-1$
                        + "'; must be READ_ONLY, FILE_MUTATION, EDT_MUTATION, or GIT_MUTATION"); //$NON-NLS-1$
            }
            // Status is always PENDING for newly created plan tasks.
            GsdTaskStatus status = GsdTaskStatus.PENDING;

            String waveId = optionalNestedString(m.get("wave_id"), //$NON-NLS-1$
                    "tasks[" + i + "].wave_id"); //$NON-NLS-1$ //$NON-NLS-2$

            List<String> dependsOn = safeStringList(m.get("depends_on"), i, "task.depends_on"); //$NON-NLS-1$ //$NON-NLS-2$

            tasks.add(new GsdTask(id, title, status, waveId, dependsOn, List.of(), executionKind));
        }
        return tasks;
    }

    @SuppressWarnings("unchecked")
    private List<GsdWave> parseWaves(List<?> rawWaves) {
        List<GsdWave> waves = new ArrayList<>();
        for (int i = 0; i < rawWaves.size(); i++) {
            Object raw = rawWaves.get(i);
            if (!(raw instanceof Map)) {
                throw new IllegalArgumentException("waves[" + i + "] is not an object"); //$NON-NLS-1$
            }
            Map<String, Object> m = (Map<String, Object>) raw;
            // Reject any key outside the allowlist.
            for (String key : m.keySet()) {
                if (!ALLOWED_WAVE_KEYS.contains(key)) {
                    throw new IllegalArgumentException(
                            "waves[" + i + "] has unexpected key '" + key + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
            }

            Object idObj = m.get("id"); //$NON-NLS-1$
            if (!(idObj instanceof String) || ((String) idObj).isBlank()) {
                throw new IllegalArgumentException("waves[" + i + "].id must be a non-blank string"); //$NON-NLS-1$
            }
            String id = (String) idObj;

            Object nameObj = m.get("name"); //$NON-NLS-1$
            if (!(nameObj instanceof String) || ((String) nameObj).isBlank()) {
                throw new IllegalArgumentException("waves[" + i + "].name must be a non-blank string"); //$NON-NLS-1$
            }
            String name = (String) nameObj;

            String goal = optionalNestedString(m.get("goal"), //$NON-NLS-1$
                    "waves[" + i + "].goal"); //$NON-NLS-1$ //$NON-NLS-2$

            List<String> taskIds = safeStringList(m.get("task_ids"), i, "wave.task_ids"); //$NON-NLS-1$ //$NON-NLS-2$

            waves.add(new GsdWave(id, name, goal != null ? goal : "", //$NON-NLS-1$
                    taskIds != null ? taskIds : List.of()));
        }
        return waves;
    }

    /** Returns null when absent and rejects values outside the declared schema. */
    private String optionalNestedString(Object obj, String field) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof String text) {
            return text;
        }
        throw new IllegalArgumentException(field + " must be a string"); //$NON-NLS-1$
    }

    /**
     * Validates that obj is a list of strings.
     * @return empty list for null, validated list otherwise
     * @throws IllegalArgumentException if not a list or contains non-strings
     */
    @SuppressWarnings("unchecked")
    private List<String> safeStringList(Object obj, int index, String field) {
        if (obj == null) {
            return List.of();
        }
        if (!(obj instanceof List<?>)) {
            throw new IllegalArgumentException(field + "[" + index + "] is not an array"); //$NON-NLS-1$
        }
        List<?> list = (List<?>) obj;
        List<String> result = new ArrayList<>(list.size());
        for (int j = 0; j < list.size(); j++) {
            Object item = list.get(j);
            if (!(item instanceof String)) {
                throw new IllegalArgumentException(field + "[" + index + "][" + j //$NON-NLS-1$
                        + "] must be a string, got " + item.getClass().getSimpleName()); //$NON-NLS-1$
            }
            result.add((String) item);
        }
        return result;
    }
}
