package com.codepilot1c.core.tools;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.metadata.AddMetadataChildRequest;
import com.codepilot1c.core.edt.metadata.EdtMetadataService;
import com.codepilot1c.core.edt.metadata.MetadataChildKind;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.metadata.MetadataOperationResult;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Tool for creating nested metadata objects for different metadata owners.
 */
public class AddMetadataChildTool implements ITool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(AddMetadataChildTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {
                  "type": "string",
                  "description": "Имя проекта EDT"
                },
                "parent_fqn": {
                  "type": "string",
                  "description": "FQN родителя: <Type>.<Name>[.<Marker>.<Name>...]"
                },
                "child_kind": {
                  "type": "string",
                  "enum": ["Attribute", "Tabular_Section", "Command", "Form", "Template", "Dimension", "Resource", "Requisite"],
                  "description": "Тип создаваемого дочернего объекта"
                },
                "name": {
                  "type": "string",
                  "description": "Имя дочернего объекта"
                },
                "synonym": {
                  "type": "string",
                  "description": "Синоним"
                },
                "comment": {
                  "type": "string",
                  "description": "Комментарий"
                },
                "properties": {
                  "type": "object",
                  "description": "Дополнительные параметры. Для batch: children=[{name,synonym,comment}]"
                }
              },
              "required": ["project", "parent_fqn", "child_kind", "name"]
            }
            """; //$NON-NLS-1$

    private final EdtMetadataService metadataService;

    public AddMetadataChildTool() {
        this(new EdtMetadataService());
    }

    AddMetadataChildTool(EdtMetadataService metadataService) {
        this.metadataService = metadataService;
    }

    @Override
    public String getName() {
        return "add_metadata_child"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Создает дочерние объекты метаданных (attribute/tabular section/command/form/template/dimension/resource/requisite)."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    @Override
    public boolean isDestructive() {
        return true;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String projectName = getString(parameters, "project"); //$NON-NLS-1$
                String parentFqn = getString(parameters, "parent_fqn"); //$NON-NLS-1$
                String childKindValue = getString(parameters, "child_kind"); //$NON-NLS-1$
                String name = getString(parameters, "name"); //$NON-NLS-1$
                String synonym = getOptionalString(parameters, "synonym"); //$NON-NLS-1$
                String comment = getOptionalString(parameters, "comment"); //$NON-NLS-1$
                Map<String, Object> properties = parameterMap(parameters.get("properties")); //$NON-NLS-1$

                if (!metadataService.isEdtAvailable()) {
                    return ToolResult.failure("EDT BM API недоступен в текущем runtime."); //$NON-NLS-1$
                }

                MetadataChildKind childKind = MetadataChildKind.fromString(childKindValue);
                AddMetadataChildRequest request = new AddMetadataChildRequest(
                        projectName, parentFqn, childKind, name, synonym, comment, properties);
                MetadataOperationResult result = metadataService.addMetadataChild(request);
                return ToolResult.success(result.formatForLlm());
            } catch (MetadataOperationException e) {
                LOG.warn("add_metadata_child failed: %s (%s)", e.getMessage(), e.getCode()); //$NON-NLS-1$
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                LOG.error("add_metadata_child failed", e); //$NON-NLS-1$
                return ToolResult.failure("Ошибка add_metadata_child: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private String getString(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String getOptionalString(Map<String, Object> parameters, String key) {
        String value = getString(parameters, key);
        return value == null || value.isBlank() ? null : value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parameterMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Collections.emptyMap();
    }
}
