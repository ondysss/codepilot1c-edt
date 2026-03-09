package com.codepilot1c.core.tools;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.forms.CreateFormRequest;
import com.codepilot1c.core.edt.forms.CreateFormResult;
import com.codepilot1c.core.edt.forms.EdtFormService;
import com.codepilot1c.core.edt.forms.FormUsage;
import com.codepilot1c.core.edt.forms.InspectFormLayoutRequest;
import com.codepilot1c.core.edt.forms.InspectFormLayoutResult;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.edt.validation.ValidationRequest;
import com.codepilot1c.core.edt.validation.ValidationResult;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * QA helper that ensures a form exists and returns its inspected layout.
 */
public class QaPrepareFormContextTool implements ITool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(QaPrepareFormContextTool.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {
                  "type": "string",
                  "description": "Имя проекта EDT"
                },
                "owner_fqn": {
                  "type": "string",
                  "description": "FQN владельца формы, например Document.ПоступлениеТоваров"
                },
                "usage": {
                  "type": "string",
                  "enum": ["OBJECT", "LIST", "CHOICE", "AUXILIARY", "object", "list", "choice", "auxiliary"],
                  "description": "Роль формы для подготовки QA контекста"
                },
                "form_name": {
                  "type": "string",
                  "description": "Явное имя формы; если не задано, вычисляется по usage и owner_fqn"
                },
                "auto_create": {
                  "type": "boolean",
                  "description": "Если inspect вернул FORM/METADATA_NOT_FOUND, создать default форму автоматически"
                },
                "set_as_default": {
                  "type": "boolean",
                  "description": "Назначить созданную форму default для заданного usage"
                },
                "wait_ms": {
                  "type": "integer",
                  "description": "Таймаут ожидания материализации формы"
                },
                "include_properties": {
                  "type": "boolean"
                },
                "include_titles": {
                  "type": "boolean"
                },
                "include_invisible": {
                  "type": "boolean"
                },
                "max_depth": {
                  "type": "integer"
                },
                "max_items": {
                  "type": "integer"
                }
              },
              "required": ["project", "owner_fqn", "usage"]
            }
            """; //$NON-NLS-1$

    private final EdtFormService formService;
    private final MetadataRequestValidationService validationService;

    public QaPrepareFormContextTool() {
        this(new EdtFormService(), new MetadataRequestValidationService());
    }

    QaPrepareFormContextTool(EdtFormService formService, MetadataRequestValidationService validationService) {
        this.formService = formService;
        this.validationService = validationService;
    }

    @Override
    public String getName() {
        return "qa_prepare_form_context"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Для QA гарантирует существование формы (при необходимости создает default форму) и возвращает inspect_form_layout."; //$NON-NLS-1$
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
            String opId = LogSanitizer.newId("qa-form-context"); //$NON-NLS-1$
            long startedAt = System.currentTimeMillis();
            LOG.info("[%s] START qa_prepare_form_context", opId); //$NON-NLS-1$
            try {
                if (parameters == null) {
                    return ToolResult.failure("QA_PREPARE_FORM_CONTEXT_ERROR: parameters are required"); //$NON-NLS-1$
                }

                String project = asString(parameters.get("project")); //$NON-NLS-1$
                String ownerFqn = asString(parameters.get("owner_fqn")); //$NON-NLS-1$
                String usageValue = asString(parameters.get("usage")); //$NON-NLS-1$
                FormUsage usage = FormUsage.fromString(usageValue);
                String formName = effectiveFormName(ownerFqn, usage, asString(parameters.get("form_name"))); //$NON-NLS-1$
                String formFqn = ownerFqn + ".Form." + formName; //$NON-NLS-1$
                boolean autoCreate = !Boolean.FALSE.equals(parameters.get("auto_create")); //$NON-NLS-1$

                InspectFormLayoutRequest inspectRequest = new InspectFormLayoutRequest(
                        project,
                        formFqn,
                        asBoolean(parameters.get("include_properties"), true), //$NON-NLS-1$
                        asBoolean(parameters.get("include_titles"), true), //$NON-NLS-1$
                        asBoolean(parameters.get("include_invisible"), true), //$NON-NLS-1$
                        asInt(parameters.get("max_depth"), 12), //$NON-NLS-1$
                        asInt(parameters.get("max_items"), 2000)); //$NON-NLS-1$

                CreateFormResult creation = null;
                InspectFormLayoutResult inspection;
                try {
                    inspection = formService.inspectFormLayout(inspectRequest);
                } catch (MetadataOperationException e) {
                    if (!autoCreate || !isFormMissing(e)) {
                        throw e;
                    }
                    creation = ensureForm(project, ownerFqn, formName, usage, parameters, opId);
                    inspection = formService.inspectFormLayout(inspectRequest);
                }

                JsonObject result = new JsonObject();
                result.addProperty("op_id", opId); //$NON-NLS-1$
                result.addProperty("status", creation == null ? "ready" : "created"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                result.addProperty("project", project); //$NON-NLS-1$
                result.addProperty("owner_fqn", ownerFqn); //$NON-NLS-1$
                result.addProperty("usage", usage.name()); //$NON-NLS-1$
                result.addProperty("form_name", formName); //$NON-NLS-1$
                result.addProperty("form_fqn", formFqn); //$NON-NLS-1$
                result.addProperty("created", creation != null); //$NON-NLS-1$
                if (creation != null) {
                    result.add("creation", GSON.toJsonTree(toCreationMap(creation))); //$NON-NLS-1$
                }
                result.add("inspection", GSON.toJsonTree(inspection)); //$NON-NLS-1$

                LOG.info("[%s] SUCCESS in %s form=%s created=%s items=%d", //$NON-NLS-1$
                        opId,
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        formFqn,
                        Boolean.valueOf(creation != null),
                        Integer.valueOf(inspection.totalItems()));
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.SEARCH_RESULTS);
            } catch (MetadataOperationException e) {
                LOG.warn("[%s] FAILED in %s: %s (%s)", opId, //$NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        e.getMessage(),
                        e.getCode());
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                LOG.error("[" + opId + "] qa_prepare_form_context failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("QA_PREPARE_FORM_CONTEXT_ERROR: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private CreateFormResult ensureForm(
            String project,
            String ownerFqn,
            String formName,
            FormUsage usage,
            Map<String, Object> parameters,
            String opId
    ) {
        Map<String, Object> payload = validationService.normalizeCreateFormPayload(
                project,
                ownerFqn,
                formName,
                usage.name(),
                Boolean.TRUE,
                asOptionalBoolean(parameters.get("set_as_default"), usage != FormUsage.AUXILIARY), //$NON-NLS-1$
                null,
                null,
                asOptionalLong(parameters.get("wait_ms"))); //$NON-NLS-1$
        ValidationResult validation = validationService.validateAndIssueToken(
                new ValidationRequest(project, ValidationOperation.CREATE_FORM, payload));
        LOG.debug("[%s] Validation token issued for create_form owner=%s form=%s", opId, ownerFqn, formName); //$NON-NLS-1$
        Map<String, Object> validatedPayload = validationService.consumeToken(
                validation.validationToken(),
                ValidationOperation.CREATE_FORM,
                project);
        CreateFormRequest request = new CreateFormRequest(
                project,
                asRequiredString(validatedPayload, "owner_fqn"), //$NON-NLS-1$
                asRequiredString(validatedPayload, "name"), //$NON-NLS-1$
                FormUsage.fromOptionalString(asOptionalString(validatedPayload, "usage")), //$NON-NLS-1$
                asOptionalBoolean(validatedPayload.get("managed")), //$NON-NLS-1$
                asOptionalBoolean(validatedPayload.get("set_as_default")), //$NON-NLS-1$
                asOptionalString(validatedPayload, "synonym"), //$NON-NLS-1$
                asOptionalString(validatedPayload, "comment"), //$NON-NLS-1$
                asOptionalLong(validatedPayload.get("wait_ms"))); //$NON-NLS-1$
        return formService.createForm(request);
    }

    private boolean isFormMissing(MetadataOperationException e) {
        return e.getCode() == MetadataOperationCode.METADATA_NOT_FOUND
                || e.getCode() == MetadataOperationCode.METADATA_PARENT_NOT_FOUND;
    }

    private static String effectiveFormName(String ownerFqn, FormUsage usage, String requestedName) {
        String trimmed = requestedName == null ? "" : requestedName.trim(); //$NON-NLS-1$
        if (!trimmed.isBlank()) {
            return trimmed;
        }
        String ownerType = topKindFromFqn(ownerFqn);
        if (usage == FormUsage.LIST) {
            return "ФормаСписка"; //$NON-NLS-1$
        }
        if (usage == FormUsage.CHOICE) {
            return "ФормаВыбора"; //$NON-NLS-1$
        }
        if (usage == FormUsage.OBJECT) {
            if ("catalog".equals(normalizeToken(ownerType))) { //$NON-NLS-1$
                return "ФормаЭлемента"; //$NON-NLS-1$
            }
            if ("document".equals(normalizeToken(ownerType))) { //$NON-NLS-1$
                return "ФормаДокумента"; //$NON-NLS-1$
            }
            return "ФормаОбъекта"; //$NON-NLS-1$
        }
        return "Форма"; //$NON-NLS-1$
    }

    private static String topKindFromFqn(String ownerFqn) {
        if (ownerFqn == null || ownerFqn.isBlank()) {
            return null;
        }
        int dot = ownerFqn.indexOf('.');
        return dot > 0 ? ownerFqn.substring(0, dot) : ownerFqn;
    }

    private static String normalizeToken(String value) {
        return value == null ? "" : value.replaceAll("[^\\p{L}\\p{Nd}]+", "").toLowerCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Map<String, Object> toCreationMap(CreateFormResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("owner_fqn", result.ownerFqn()); //$NON-NLS-1$
        map.put("form_fqn", result.formFqn()); //$NON-NLS-1$
        map.put("usage", result.usage() == null ? null : result.usage().name()); //$NON-NLS-1$
        map.put("default_assigned", Boolean.valueOf(result.defaultAssigned())); //$NON-NLS-1$
        map.put("materialized", Boolean.valueOf(result.materialized())); //$NON-NLS-1$
        map.put("form_file_path", result.formFilePath()); //$NON-NLS-1$
        map.put("module_file_path", result.moduleFilePath()); //$NON-NLS-1$
        map.put("diagnostics", result.diagnostics()); //$NON-NLS-1$
        return map;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean asBoolean(Object value, boolean defaultValue) {
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

    private static int asInt(Object value, int defaultValue) {
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

    private static String asRequiredString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String asOptionalString(Map<String, Object> payload, String key) {
        String value = asRequiredString(payload, key);
        return value == null || value.isBlank() ? null : value;
    }

    private static Boolean asOptionalBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        return Boolean.valueOf(Boolean.parseBoolean(text));
    }

    private static Boolean asOptionalBoolean(Object value, boolean defaultValue) {
        Boolean parsed = asOptionalBoolean(value);
        return parsed == null ? Boolean.valueOf(defaultValue) : parsed;
    }

    private static Long asOptionalLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return Long.valueOf(number.longValue());
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        return Long.valueOf(text);
    }
}
