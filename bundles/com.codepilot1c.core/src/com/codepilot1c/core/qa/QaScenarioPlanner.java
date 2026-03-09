package com.codepilot1c.core.qa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class QaScenarioPlanner {

    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}"); //$NON-NLS-1$

    public QaScenarioPlan plan(PlanRequest request, QaStepRegistry registry) {
        Map<String, String> context = buildContext(request);
        QaRecipe recipe = resolveRecipe(request, registry);
        List<String> unresolved = new ArrayList<>();
        List<QaScenarioStep> steps = new ArrayList<>();
        for (QaScenarioStep recipeStep : recipe.steps()) {
            steps.add(resolveTemplates(recipeStep, context, unresolved));
        }
        appendDynamicFormSteps(request, steps);
        appendDynamicTableSteps(request, steps);
        if (request.closeCurrentWindow()) {
            steps.add(new QaScenarioStep("ui.close_current_window", Map.of())); //$NON-NLS-1$
        }
        if (shouldCloseTestClient(request, recipe)) {
            steps.add(new QaScenarioStep("client.close_named", Map.of("client_name", "Этот клиент"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        String scenarioTitle = request.scenarioTitle();
        if (scenarioTitle == null || scenarioTitle.isBlank()) {
            scenarioTitle = recipe.title();
            if (context.containsKey("object_name")) { //$NON-NLS-1$
                scenarioTitle = scenarioTitle + ": " + context.get("object_name"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return new QaScenarioPlan(scenarioTitle, recipe.id(), context, steps, List.copyOf(new LinkedHashSet<>(unresolved)),
                request.tags());
    }

    private static Map<String, String> buildContext(PlanRequest request) {
        Map<String, String> context = new LinkedHashMap<>();
        if (request.context() != null) {
            context.putAll(request.context());
        }
        putIfHasText(context, "goal", request.goal()); //$NON-NLS-1$
        putIfHasText(context, "object_name", request.objectName()); //$NON-NLS-1$
        putIfHasText(context, "object_type", request.objectType()); //$NON-NLS-1$
        putIfHasText(context, "section_name", request.sectionName()); //$NON-NLS-1$
        putIfHasText(context, "table_name", request.tableName()); //$NON-NLS-1$
        if (!context.containsKey("section_name") && context.containsKey("object_name")) { //$NON-NLS-1$ //$NON-NLS-2$
            context.put("section_name", context.get("object_name")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!context.containsKey("table_name")) { //$NON-NLS-1$
            context.put("table_name", "Товары"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!context.containsKey("create_window_title")) { //$NON-NLS-1$
            String displayName = resolveDisplayName(context);
            if (displayName != null) {
                context.put("create_window_title", displayName + " (создание)"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return context;
    }

    private static String resolveDisplayName(Map<String, String> context) {
        String displayName = trimToNull(context.get("object_display_name")); //$NON-NLS-1$
        if (displayName != null) {
            return displayName;
        }
        displayName = trimToNull(context.get("section_name")); //$NON-NLS-1$
        if (displayName != null) {
            return displayName;
        }
        return trimToNull(context.get("object_name")); //$NON-NLS-1$
    }

    private static QaRecipe resolveRecipe(PlanRequest request, QaStepRegistry registry) {
        if (request.recipeId() != null && !request.recipeId().isBlank()) {
            return registry.findRecipe(request.recipeId())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown QA recipe: " + request.recipeId())); //$NON-NLS-1$
        }
        return registry.inferRecipe(request.goal(), request.objectType())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unable to infer QA recipe from goal; provide recipe_id")); //$NON-NLS-1$
    }

    private static QaScenarioStep resolveTemplates(QaScenarioStep step, Map<String, String> context,
                                                   List<String> unresolved) {
        Map<String, String> args = new LinkedHashMap<>();
        if (step.args() != null) {
            for (Map.Entry<String, String> entry : step.args().entrySet()) {
                args.put(entry.getKey(), resolveTemplate(entry.getValue(), context, unresolved));
            }
        }
        return new QaScenarioStep(step.intent(), args);
    }

    private static String resolveTemplate(String template, Map<String, String> context, List<String> unresolved) {
        if (template == null) {
            return null;
        }
        Matcher matcher = TEMPLATE_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = context.get(key);
            if (value == null || value.isBlank()) {
                unresolved.add(key);
                matcher.appendReplacement(sb, Matcher.quoteReplacement("${" + key + "}")); //$NON-NLS-1$ //$NON-NLS-2$
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static void appendDynamicFormSteps(PlanRequest request, List<QaScenarioStep> steps) {
        if (request.pickFields() != null) {
            for (String field : request.pickFields()) {
                if (field == null || field.isBlank()) {
                    continue;
                }
                steps.add(new QaScenarioStep("form.pick_from_list", Map.of("field", field))); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        if (request.textFields() != null) {
            for (Map<String, String> field : request.textFields()) {
                if (field == null) {
                    continue;
                }
                String name = trimToNull(field.get("field")); //$NON-NLS-1$
                String value = trimToNull(field.get("value")); //$NON-NLS-1$
                if (name == null || value == null) {
                    continue;
                }
                steps.add(new QaScenarioStep("field.set_text", Map.of("field", name, "value", value))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }
    }

    private static void appendDynamicTableSteps(PlanRequest request, List<QaScenarioStep> steps) {
        if (request.tableActions() == null || request.tableActions().isEmpty()) {
            return;
        }
        Set<String> addedTables = new LinkedHashSet<>();
        for (Map<String, String> action : request.tableActions()) {
            if (action == null) {
                continue;
            }
            String table = trimToNull(action.get("table")); //$NON-NLS-1$
            if (table == null) {
                table = request.tableName();
            }
            if (table == null || table.isBlank()) {
                table = "Товары"; //$NON-NLS-1$
            }
            String actionType = trimToNull(action.get("action")); //$NON-NLS-1$
            if (actionType == null) {
                continue;
            }
            if (!addedTables.contains(table)) {
                steps.add(new QaScenarioStep("table.add_row", Map.of("table", table))); //$NON-NLS-1$ //$NON-NLS-2$
                addedTables.add(table);
            }
            switch (actionType.toLowerCase(Locale.ROOT)) {
            case "pick_from_list": //$NON-NLS-1$
                String field = trimToNull(action.get("field")); //$NON-NLS-1$
                if (field != null) {
                    steps.add(new QaScenarioStep("table.pick_from_list", Map.of("table", table, "field", field))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                break;
            case "set_text": //$NON-NLS-1$
                String textField = trimToNull(action.get("field")); //$NON-NLS-1$
                String value = trimToNull(action.get("value")); //$NON-NLS-1$
                if (textField != null && value != null) {
                    steps.add(new QaScenarioStep("table.set_text",
                            Map.of("table", table, "field", textField, "value", value))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                break;
            case "finish_edit": //$NON-NLS-1$
                steps.add(new QaScenarioStep("table.finish_edit_row", Map.of("table", table))); //$NON-NLS-1$ //$NON-NLS-2$
                break;
            default:
                break;
            }
        }
    }

    private static void putIfHasText(Map<String, String> context, String key, String value) {
        if (value != null && !value.isBlank()) {
            context.put(key, value);
        }
    }

    private static boolean shouldCloseTestClient(PlanRequest request, QaRecipe recipe) {
        if (request.closeTestClient() != null) {
            return request.closeTestClient().booleanValue();
        }
        return isUiRecipe(recipe);
    }

    private static boolean isUiRecipe(QaRecipe recipe) {
        if (recipe == null || recipe.id() == null) {
            return false;
        }
        return switch (recipe.id()) {
        case "create_catalog_item", "create_document_draft", "open_navigation_smoke" -> true; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        default -> false;
        };
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record PlanRequest(
            String goal,
            String scenarioTitle,
            String recipeId,
            String objectName,
            String objectType,
            String sectionName,
            String tableName,
            List<String> pickFields,
            List<Map<String, String>> textFields,
            List<Map<String, String>> tableActions,
            boolean closeCurrentWindow,
            Boolean closeTestClient,
            Map<String, String> context,
            List<String> tags) {
    }
}
