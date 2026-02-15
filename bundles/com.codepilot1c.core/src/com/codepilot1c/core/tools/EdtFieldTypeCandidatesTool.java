package com.codepilot1c.core.tools;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.metadata.EdtMetadataService;
import com.codepilot1c.core.edt.metadata.FieldTypeCandidatesRequest;
import com.codepilot1c.core.edt.metadata.FieldTypeCandidatesResult;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.google.gson.Gson;

/**
 * Returns EDT TypeProvider candidates for a metadata field.
 */
public class EdtFieldTypeCandidatesTool implements ITool {

    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {"type": "string", "description": "Имя EDT проекта"},
                "target_fqn": {"type": "string", "description": "FQN объекта метаданных (например Catalog.Контрагенты.Attribute.КПП)"},
                "field": {"type": "string", "description": "Имя поля (по умолчанию type)"},
                "limit": {"type": "integer", "description": "Максимум типов в ответе (1..1000, default=200)"}
              },
              "required": ["project", "target_fqn"]
            }
            """; //$NON-NLS-1$

    private final EdtMetadataService metadataService;

    public EdtFieldTypeCandidatesTool() {
        this(new EdtMetadataService());
    }

    EdtFieldTypeCandidatesTool(EdtMetadataService metadataService) {
        this.metadataService = metadataService;
    }

    @Override
    public String getName() {
        return "edt_field_type_candidates"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Возвращает список допустимых EDT-типов для указанного поля объекта метаданных."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                FieldTypeCandidatesRequest request = new FieldTypeCandidatesRequest(
                        asString(parameters.get("project")), //$NON-NLS-1$
                        asString(parameters.get("target_fqn")), //$NON-NLS-1$
                        asString(parameters.get("field")), //$NON-NLS-1$
                        asInteger(parameters.get("limit"))); //$NON-NLS-1$
                FieldTypeCandidatesResult result = metadataService.listFieldTypeCandidates(request);
                return ToolResult.success(GSON.toJson(result));
            } catch (MetadataOperationException e) {
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                return ToolResult.failure("INTERNAL_ERROR: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return Integer.valueOf(number.intValue());
        }
        String raw = String.valueOf(value);
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
