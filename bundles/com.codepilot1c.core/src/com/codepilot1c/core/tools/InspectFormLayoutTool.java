package com.codepilot1c.core.tools;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.forms.EdtFormService;
import com.codepilot1c.core.edt.forms.InspectFormLayoutRequest;
import com.codepilot1c.core.edt.forms.InspectFormLayoutResult;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.google.gson.Gson;

/**
 * Tool for headless managed form layout inspection via EDT BM API.
 */
public class InspectFormLayoutTool implements ITool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(InspectFormLayoutTool.class);
    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {
                  "type": "string",
                  "description": "Имя проекта EDT"
                },
                "form_fqn": {
                  "type": "string",
                  "description": "FQN формы, например Document.РасходнаяНакладная.Form.ФормаДокумента"
                },
                "include_properties": {
                  "type": "boolean",
                  "description": "Включить дополнительные scalar-свойства узлов формы"
                },
                "include_titles": {
                  "type": "boolean",
                  "description": "Включить мультиязычные заголовки элементов"
                },
                "include_invisible": {
                  "type": "boolean",
                  "description": "Включить невидимые элементы формы"
                },
                "max_depth": {
                  "type": "integer",
                  "description": "Максимальная глубина обхода дерева элементов (1..64)"
                },
                "max_items": {
                  "type": "integer",
                  "description": "Максимальное количество элементов в ответе (1..10000)"
                }
              },
              "required": ["project", "form_fqn"]
            }
            """; //$NON-NLS-1$

    private final EdtFormService formService;

    public InspectFormLayoutTool() {
        this(new EdtFormService());
    }

    InspectFormLayoutTool(EdtFormService formService) {
        this.formService = formService;
    }

    @Override
    public String getName() {
        return "inspect_form_layout"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Читает структуру управляемой формы через EDT BM API без active editor (дерево элементов, id, dataPath, свойства)."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("inspect-form-layout"); //$NON-NLS-1$
            long startedAt = System.currentTimeMillis();
            LOG.info("[%s] START inspect_form_layout", opId); //$NON-NLS-1$
            try {
                String project = asString(parameters.get("project")); //$NON-NLS-1$
                String formFqn = asString(parameters.get("form_fqn")); //$NON-NLS-1$
                boolean includeProperties = asBoolean(parameters.get("include_properties"), true); //$NON-NLS-1$
                boolean includeTitles = asBoolean(parameters.get("include_titles"), true); //$NON-NLS-1$
                boolean includeInvisible = asBoolean(parameters.get("include_invisible"), true); //$NON-NLS-1$
                int maxDepth = asInt(parameters.get("max_depth"), 12); //$NON-NLS-1$
                int maxItems = asInt(parameters.get("max_items"), 2000); //$NON-NLS-1$

                InspectFormLayoutRequest request = new InspectFormLayoutRequest(
                        project,
                        formFqn,
                        includeProperties,
                        includeTitles,
                        includeInvisible,
                        maxDepth,
                        maxItems);
                InspectFormLayoutResult result = formService.inspectFormLayout(request);
                LOG.info("[%s] SUCCESS in %s form=%s items=%d truncated=%s", //$NON-NLS-1$
                        opId,
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        result.formFqn(),
                        Integer.valueOf(result.totalItems()),
                        Boolean.valueOf(result.truncated()));
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.SEARCH_RESULTS);
            } catch (MetadataOperationException e) {
                LOG.warn("[%s] FAILED in %s: %s (%s)", opId, //$NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        e.getMessage(),
                        e.getCode());
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                LOG.error("[" + opId + "] inspect_form_layout failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("Ошибка inspect_form_layout: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean asBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool.booleanValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(text);
    }

    private int asInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
