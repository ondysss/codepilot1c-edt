package com.codepilot1c.core.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.ResourcesPlugin;

import com.codepilot1c.core.edt.ast.EdtAstServices;
import com.codepilot1c.core.edt.ast.MetadataIndexRequest;
import com.codepilot1c.core.edt.ast.MetadataIndexResult;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.qa.QaScenarioPlan;
import com.codepilot1c.core.qa.QaScenarioPlanner;
import com.codepilot1c.core.qa.QaStepRegistry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

public class QaPlanScenarioTool implements ITool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(QaPlanScenarioTool.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "goal": {
                  "type": "string",
                  "description": "Цель сценария или пользовательская задача"
                },
                "scenario_title": {
                  "type": "string",
                  "description": "Явное название сценария"
                },
                "project_name": {
                  "type": "string",
                  "description": "Имя EDT проекта; если не задано, берется первый открытый проект"
                },
                "recipe_id": {
                  "type": "string",
                  "description": "Явный recipe id, если его нужно зафиксировать"
                },
                "object_name": {
                  "type": "string",
                  "description": "Имя объекта метаданных (например ПоступлениеТоваров или Контрагенты)"
                },
                "object_type": {
                  "type": "string",
                  "description": "Тип объекта (Document, Catalog, справочник, документ)"
                },
                "section_name": {
                  "type": "string",
                  "description": "Имя раздела навигации, если сценарий открывает раздел"
                },
                "table_name": {
                  "type": "string",
                  "description": "Имя основной табличной части"
                },
                "pick_fields": {
                  "type": "array",
                  "items": { "type": "string" }
                },
                "text_fields": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "field": { "type": "string" },
                      "value": { "type": "string" }
                    },
                    "required": ["field", "value"]
                  }
                },
                "table_actions": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "action": { "type": "string" },
                      "table": { "type": "string" },
                      "field": { "type": "string" },
                      "value": { "type": "string" }
                    },
                    "required": ["action"]
                  }
                },
                "close_current_window": {
                  "type": "boolean"
                },
                "close_test_client": {
                  "type": "boolean",
                  "description": "Явно добавить в конец сценария шаг закрытия главного окна TestClient"
                },
                "context": {
                  "type": "object",
                  "additionalProperties": { "type": "string" }
                },
                "tags": {
                  "type": "array",
                  "items": { "type": "string" }
                }
              },
              "required": ["goal"]
            }
            """; //$NON-NLS-1$

    @Override
    public String getName() {
        return "qa_plan_scenario"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Строит структурированный QA scenario plan из цели и контекста, без ручного написания Gherkin."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("qa-plan"); //$NON-NLS-1$
            LOG.info("[%s] START qa_plan_scenario", opId); //$NON-NLS-1$
            try {
                if (parameters == null) {
                    return ToolResult.failure("QA_PLAN_SCENARIO_ERROR: parameters are required"); //$NON-NLS-1$
                }
                String goal = asString(parameters.get("goal")); //$NON-NLS-1$
                if (goal == null || goal.isBlank()) {
                    return ToolResult.failure("QA_PLAN_SCENARIO_ERROR: goal is required"); //$NON-NLS-1$
                }
                String projectName = asString(parameters.get("project_name")); //$NON-NLS-1$
                Map<String, String> context = enrichContext(
                        resolveProjectName(projectName),
                        asString(parameters.get("object_name")), //$NON-NLS-1$
                        asString(parameters.get("object_type")), //$NON-NLS-1$
                        asStringMap(parameters.get("context"))); //$NON-NLS-1$
                QaScenarioPlanner.PlanRequest request = new QaScenarioPlanner.PlanRequest(
                        goal,
                        asString(parameters.get("scenario_title")), //$NON-NLS-1$
                        asString(parameters.get("recipe_id")), //$NON-NLS-1$
                        asString(parameters.get("object_name")), //$NON-NLS-1$
                        asString(parameters.get("object_type")), //$NON-NLS-1$
                        asString(parameters.get("section_name")), //$NON-NLS-1$
                        asString(parameters.get("table_name")), //$NON-NLS-1$
                        asStringList(parameters.get("pick_fields")), //$NON-NLS-1$
                        asMapList(parameters.get("text_fields")), //$NON-NLS-1$
                        asMapList(parameters.get("table_actions")), //$NON-NLS-1$
                        Boolean.TRUE.equals(parameters.get("close_current_window")), //$NON-NLS-1$
                        parameters.containsKey("close_test_client") //$NON-NLS-1$
                                ? Boolean.valueOf(Boolean.TRUE.equals(parameters.get("close_test_client"))) //$NON-NLS-1$
                                : null,
                        context,
                        asStringList(parameters.get("tags"))); //$NON-NLS-1$
                QaScenarioPlan plan = new QaScenarioPlanner().plan(request, QaStepRegistry.loadDefault());
                JsonObject result = new JsonObject();
                result.addProperty("op_id", opId); //$NON-NLS-1$
                result.addProperty("status", plan.readyForCompile() ? "planned" : "needs_input"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                result.add("plan", GSON.toJsonTree(plan)); //$NON-NLS-1$
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.CODE);
            } catch (Exception e) {
                LOG.error("[" + opId + "] qa_plan_scenario failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("QA_PLAN_SCENARIO_ERROR: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private static Map<String, String> enrichContext(String projectName, String objectName, String objectType,
            Map<String, String> rawContext) {
        Map<String, String> context = new LinkedHashMap<>(rawContext);
        if (projectName == null || projectName.isBlank() || objectName == null || objectName.isBlank()) {
            return context;
        }
        String displayName = resolveObjectDisplayName(projectName, objectName, objectType);
        if (displayName == null || displayName.isBlank()) {
            return context;
        }
        context.put("object_display_name", displayName); //$NON-NLS-1$
        context.put("section_name", displayName); //$NON-NLS-1$
        context.put("create_window_title", displayName + " (создание)"); //$NON-NLS-1$ //$NON-NLS-2$
        return context;
    }

    private static String resolveObjectDisplayName(String projectName, String objectName, String objectType) {
        try {
            String scope = objectType == null ? null : objectType.trim();
            MetadataIndexResult result = EdtAstServices.getInstance()
                    .scanMetadataIndex(new MetadataIndexRequest(projectName, scope, objectName, 20, "ru")); //$NON-NLS-1$
            if (result == null || result.getItems() == null) {
                return null;
            }
            for (MetadataIndexResult.Item item : result.getItems()) {
                if (item == null) {
                    continue;
                }
                if (!objectName.equals(item.getName())) {
                    continue;
                }
                String synonym = trimToNull(item.getSynonym());
                if (synonym != null) {
                    return synonym;
                }
                return trimToNull(item.getName());
            }
        } catch (Exception e) {
            LOG.warn("Failed to resolve metadata display name for QA plan: " + e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static List<String> asStringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !item.toString().isBlank()) {
                    result.add(item.toString().trim());
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> asMapList(Object value) {
        List<Map<String, String>> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, String> converted = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (entry.getKey() != null && entry.getValue() != null) {
                            converted.put(entry.getKey().toString(), entry.getValue().toString());
                        }
                    }
                    result.add(converted);
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> asStringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                result.put(entry.getKey().toString(), entry.getValue().toString());
            }
        }
        return result;
    }

    private static String resolveProjectName(String projectNameParam) {
        if (projectNameParam != null && !projectNameParam.isBlank()) {
            return projectNameParam;
        }
        var projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        if (projects != null) {
            for (var project : projects) {
                if (project != null && project.isOpen()) {
                    return project.getName();
                }
            }
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
