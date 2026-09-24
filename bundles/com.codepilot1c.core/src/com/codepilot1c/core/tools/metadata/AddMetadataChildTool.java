package com.codepilot1c.core.tools.metadata;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.metadata.AddMetadataChildRequest;
import com.codepilot1c.core.edt.metadata.EdtMetadataService;
import com.codepilot1c.core.edt.metadata.MetadataChildKind;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.metadata.MetadataOperationResult;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Tool for creating nested metadata objects for different metadata owners.
 */
@ToolMeta(name = "add_metadata_child", category = "metadata", mutating = true, requiresValidationToken = true, tags = {"workspace", "edt"})
public class AddMetadataChildTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(AddMetadataChildTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {
                  "type": "string",
                  "description": "Имя EDT проекта, где уже существует owner object."
                },
                "parent_fqn": {
                  "type": "string",
                  "description": "FQN существующего owner object. Use this tool only when parent already exists."
                },
                "child_kind": {
                  "type": "string",
                  "enum": ["Attribute", "Tabular_Section", "Command", "Form", "Template", "Dimension", "Resource", "Requisite", "EnumValue", "URLTemplate", "HTTPMethod"],
                  "description": "Kind of new child object. Do not use for top-level objects. Use EnumValue to add a child value to an existing Enum parent (create_metadata cannot create enum values). Use URLTemplate under an HTTPService parent and HTTPMethod under a URLTemplate parent (parent_fqn = HTTPService.<Name>.URLTemplate.<TemplateName>)."
                },
                "name": {
                  "type": "string",
                  "description": "Name of the new child object. Optional when properties.children contains a batch."
                },
                "synonym": {
                  "type": "string",
                  "description": "Синоним"
                },
                "comment": {
                  "type": "string",
                  "description": "Комментарий"
                },
                "form_usage": {
                  "type": "string",
                  "enum": ["OBJECT", "RECORD", "LIST", "CHOICE", "AUXILIARY", "object", "record", "list", "choice", "auxiliary"],
                  "description": "Роль формы (используется при child_kind=Form). RECORD — форма записи регистра сведений (defaultRecordForm); OBJECT у регистра сведений означает то же. У перечисления и регистров накопления, бухгалтерии, расчёта формы объекта нет — OBJECT отклоняется."
                },
                "managed": {
                  "type": "boolean",
                  "description": "Тип формы (MVP: только true)"
                },
                "set_as_default": {
                  "type": "boolean",
                  "description": "Назначить форму default для owner по form_usage"
                },
                "wait_ms": {
                  "type": "integer",
                  "description": "Таймаут ожидания материализации формы в файлы"
                },
                "template": {
                  "type": "string",
                  "description": "Точный путь URL-шаблона (child_kind=URLTemplate), например \\"/state\\" или \\"/items/{id}\\". Без пробелов, query и fragment."
                },
                "http_method": {
                  "type": "string",
                  "enum": ["GET", "POST", "PUT", "DELETE", "PATCH", "MERGE", "OPTIONS", "TRACE", "CONNECT", "PROPFIND", "PROPPATCH", "MOVE", "COPY", "LOCK", "UNLOCK", "MKCOL", "ANY"],
                  "description": "HTTP-метод (child_kind=HTTPMethod). Соответствует перечислению EDT HTTPMethod."
                },
                "handler": {
                  "type": "string",
                  "description": "Имя процедуры-обработчика в модуле HTTP-сервиса (child_kind=HTTPMethod)."
                },
                "template_type": {
                  "type": "string",
                  "enum": ["spreadsheet", "html", "text", "binary", "dcs", "active_document"],
                  "description": "Тип макета (используется при child_kind=Template). По умолчанию spreadsheet (табличный документ .mxl)"
                },
                "properties": {
                  "type": "object",
                  "description": "Дополнительные параметры. Для batch: children=[{name,synonym,comment}]"
                },
                "validation_token": {
                  "type": "string",
                  "description": "Одноразовый токен из edt_validate_request for this exact child-creation request."
                }
              },
              "required": ["project", "parent_fqn", "child_kind", "validation_token"]
            }
            """; //$NON-NLS-1$

    private final EdtMetadataService metadataService;
    private final MetadataRequestValidationService validationService;

    public AddMetadataChildTool() {
        this(new EdtMetadataService(), new MetadataRequestValidationService());
    }

    AddMetadataChildTool(EdtMetadataService metadataService, MetadataRequestValidationService validationService) {
        this.metadataService = metadataService;
        this.validationService = validationService;
    }

    @Override
    public String getDescription() {
        return "Создаёт дочерний объект метаданных под существующим владельцем через EDT BM API. " //$NON-NLS-1$
                + "Поддерживает Attribute, Tabular_Section, Command, Form, Template, Dimension, Resource, Requisite, EnumValue, " //$NON-NLS-1$
                + "URLTemplate (под HTTPService) и HTTPMethod (под URLTemplate)."; //$NON-NLS-1$
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
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> parameters = params.getRaw();
            String opId = LogSanitizer.newId("add-child"); //$NON-NLS-1$
            long startedAt = System.currentTimeMillis();
            LOG.info("[%s] START add_metadata_child", opId); //$NON-NLS-1$
            LOG.debug("[%s] Raw parameters: %s", opId, // $NON-NLS-1$
                    LogSanitizer.truncate(LogSanitizer.redactSecrets(String.valueOf(parameters)), 4000));
            try {
                String projectName = getString(parameters, "project"); //$NON-NLS-1$
                String parentFqn = getString(parameters, "parent_fqn"); //$NON-NLS-1$
                String childKindValue = getString(parameters, "child_kind"); //$NON-NLS-1$
                String name = getString(parameters, "name"); //$NON-NLS-1$
                String synonym = getOptionalString(parameters, "synonym"); //$NON-NLS-1$
                String comment = getOptionalString(parameters, "comment"); //$NON-NLS-1$
                Map<String, Object> properties = parameterMap(parameters.get("properties")); //$NON-NLS-1$
                Map<String, Object> normalizedProperties = mergeFormOptions(properties, parameters, childKindValue);
                String validationToken = getString(parameters, "validation_token"); //$NON-NLS-1$

                Map<String, Object> normalizedPayload = validationService.normalizeAddChildPayload(
                        projectName, parentFqn, childKindValue, name, synonym, comment, normalizedProperties);
                LOG.debug("[%s] Normalized payload: %s", opId, // $NON-NLS-1$
                        LogSanitizer.truncate(LogSanitizer.redactSecrets(String.valueOf(normalizedPayload)), 4000));
                Map<String, Object> validatedPayload = validationService.consumeToken(
                        validationToken,
                        ValidationOperation.ADD_METADATA_CHILD,
                        projectName);
                LOG.debug("[%s] Validation token consumed successfully", opId); //$NON-NLS-1$
                LOG.debug("[%s] Validated payload from token: %s", opId, // $NON-NLS-1$
                        LogSanitizer.truncate(LogSanitizer.redactSecrets(String.valueOf(validatedPayload)), 4000));

                if (!validatedPayload.equals(normalizedPayload)) {
                    LOG.warn("[%s] Input payload differs from validated payload, applying validated payload from token", opId); //$NON-NLS-1$
                }
                MetadataChildKind childKind = MetadataChildKind.fromString(asRequiredString(validatedPayload, "child_kind")); //$NON-NLS-1$
                String validatedParentFqn = asRequiredString(validatedPayload, "parent_fqn"); //$NON-NLS-1$
                String validatedName = asOptionalString(validatedPayload, "name"); //$NON-NLS-1$
                String validatedSynonym = asOptionalString(validatedPayload, "synonym"); //$NON-NLS-1$
                String validatedComment = asOptionalString(validatedPayload, "comment"); //$NON-NLS-1$
                Map<String, Object> validatedProperties = parameterMap(validatedPayload.get("properties")); //$NON-NLS-1$
                AddMetadataChildRequest request = new AddMetadataChildRequest(
                        projectName, validatedParentFqn, childKind, validatedName, validatedSynonym, validatedComment, validatedProperties);
                LOG.info("[%s] Calling EdtMetadataService.addMetadataChild(project=%s, parent=%s, kind=%s, name=%s)", // $NON-NLS-1$
                        opId, projectName, validatedParentFqn, childKind, validatedName);
                MetadataOperationResult result = metadataService.addMetadataChild(request);
                LOG.info("[%s] SUCCESS in %s, fqn=%s", opId, // $NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        result.fqn());
                return ToolResult.success(result.formatForLlm());
            } catch (MetadataOperationException e) {
                LOG.warn("[%s] FAILED in %s: %s (%s)", opId, // $NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        e.getMessage(),
                        e.getCode());
                if (e.getCode() == com.codepilot1c.core.edt.metadata.MetadataOperationCode.EDT_TRANSACTION_FAILED) {
                    LOG.error("[" + opId + "] add_metadata_child EDT transaction error details", e); //$NON-NLS-1$ //$NON-NLS-2$
                } else if (e.getCode() == com.codepilot1c.core.edt.metadata.MetadataOperationCode.PROJECT_NOT_READY) {
                    LOG.error("[" + opId + "] add_metadata_child readiness error details", e); //$NON-NLS-1$ //$NON-NLS-2$
                }
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                LOG.error("[" + opId + "] add_metadata_child failed", e); //$NON-NLS-1$ //$NON-NLS-2$
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

    private String asRequiredString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_NAME,
                    "Required field missing in validated payload: " + key, //$NON-NLS-1$
                    false);
        }
        return String.valueOf(value);
    }

    private String asOptionalString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parameterMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Collections.emptyMap();
    }

    private Map<String, Object> mergeFormOptions(
            Map<String, Object> baseProperties,
            Map<String, Object> parameters,
            String childKindValue
    ) {
        String normalizedKind = childKindValue == null ? "" : childKindValue.toLowerCase(java.util.Locale.ROOT); //$NON-NLS-1$
        MetadataChildKind httpKind = tryParseHttpKind(childKindValue);
        if (httpKind != null) {
            Map<String, Object> merged = new LinkedHashMap<>();
            if (baseProperties != null && !baseProperties.isEmpty()) {
                merged.putAll(baseProperties);
            }
            if (httpKind == MetadataChildKind.HTTP_URL_TEMPLATE) {
                putIfPresent(merged, "template", parameters.get("template")); //$NON-NLS-1$ //$NON-NLS-2$
            } else {
                putIfPresent(merged, "http_method", parameters.get("http_method")); //$NON-NLS-1$ //$NON-NLS-2$
                putIfPresent(merged, "handler", parameters.get("handler")); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return merged;
        }
        if ("template".equals(normalizedKind) || "макет".equals(normalizedKind)) { //$NON-NLS-1$ //$NON-NLS-2$
            Map<String, Object> merged = new LinkedHashMap<>();
            if (baseProperties != null && !baseProperties.isEmpty()) {
                merged.putAll(baseProperties);
            }
            putIfPresent(merged, "template_type", parameters.get("template_type")); //$NON-NLS-1$ //$NON-NLS-2$
            return merged;
        }
        // One list and one kind check with the validate step: the token has to carry exactly this merge.
        if (!MetadataRequestValidationService.isFormChildKind(childKindValue)) {
            return baseProperties;
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        if (baseProperties != null && !baseProperties.isEmpty()) {
            merged.putAll(baseProperties);
        }
        for (String formOption : MetadataRequestValidationService.ADD_CHILD_FORM_OPTIONS) {
            putIfPresent(merged, formOption, parameters.get(formOption));
        }
        return merged;
    }

    /**
     * Returns the HTTP service child kind for this request, or {@code null} for every other kind
     * (including an unparseable one, which the validation service rejects with the canonical error).
     */
    private MetadataChildKind tryParseHttpKind(String childKindValue) {
        try {
            MetadataChildKind kind = MetadataChildKind.fromString(childKindValue);
            return com.codepilot1c.core.edt.metadata.HttpServiceChildProperties.applies(kind) ? kind : null;
        } catch (MetadataOperationException e) {
            return null;
        }
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String text && text.isBlank()) {
            return;
        }
        target.put(key, value);
    }
}
