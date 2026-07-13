package com.codepilot1c.core.tools.forms;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.forms.EdtFormService;
import com.codepilot1c.core.edt.forms.UpdateFormModelRequest;
import com.codepilot1c.core.edt.forms.UpdateFormModelResult;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Tool for headless form model mutation via EDT BM API.
 */
@ToolMeta(name = "mutate_form_model", category = "forms", mutating = true, requiresValidationToken = true, tags = {"workspace", "edt"})
public class MutateFormModelTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(MutateFormModelTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {
                  "type": "string",
                  "description": "Имя EDT проекта, containing an already existing managed form."
                },
                "form_fqn": {
                  "type": "string",
                  "description": "FQN of an existing managed form to modify. Use create_form when the form does not exist yet."
                },
                "operations": {
                  "type": "array",
                  "minItems": 1,
                  "items": {
                    "type": "object",
                    "properties": {
                      "op": {
                        "type": "string",
                        "enum": ["set_form_props", "add_group", "add_field", "add_table", "add_command", "add_button", "set_item", "remove_item", "move_item", "add_event_handler", "set_event_handler", "remove_event_handler"],
                        "description": "Visual form operation. add_field creates a simple UI field; add_table creates a Table item and, with data_path to a ValueTable/ValueTree/tabular attribute, auto-generates its columns. Neither creates the form attribute — create attributes with apply_form_recipe first."
                      },
                      "data_path": {
                        "type": "string",
                        "description": "For add_field/add_table/set_item, data_path must reference an existing form attribute. For add_table point it at a ValueTable/ValueTree/tabular attribute to auto-generate columns. Use inspect_form_layout to confirm names."
                      },
                      "type": {
                        "type": "string",
                        "description": "Visual item type, e.g. INPUT_FIELD or LABEL_FIELD. Do not guess SpreadsheetDocument/ТабличныйДокумент as attribute type."
                      },
                      "set": {
                        "type": "object",
                        "description": "Properties for the existing form, item, or attribute patch. type here changes visual widget type unless patching form attributes."
                      },
                      "name": {
                        "type": "string",
                        "description": "Name of the new item for add_field/add_table/add_group/add_command/add_button."
                      },
                      "parent_item_id": {
                        "type": "integer",
                        "description": "Id of the parent container (group/table) from inspect_form_layout. Defaults to the form root."
                      },
                      "parent_item_name": {
                        "type": "string",
                        "description": "Parent container by name — alternative to parent_item_id. Use it to target an item created earlier in this SAME operations batch (e.g. a group from a prior add_group) whose id is not yet known."
                      },
                      "item_id": {
                        "type": "integer",
                        "description": "Target item id for set_item/remove_item/move_item (from inspect_form_layout)."
                      },
                      "event": {
                        "type": "string",
                        "description": "Event name (EN or RU) for add_event_handler/set_event_handler/remove_event_handler; validated at runtime against the item's allowed events."
                      },
                      "handler_name": {
                        "type": "string",
                        "description": "BSL handler procedure name for the event op. Strongly recommended; a deterministic name is used when omitted."
                      },
                      "target": {
                        "type": "string",
                        "description": "For event ops: 'form' for form-level events, or omit and pass item_id/item_name for a field/table event."
                      }
                    },
                    "required": ["op"],
                    "additionalProperties": true
                  },
                  "description": "Visual form items only: groups, fields, commands, buttons. Precondition: call inspect_form_layout when item ids or form attributes are unknown. add_field{parent_item_id,name,data_path,type} binds a UI field to an existing form attribute; it does not create that attribute. Use apply_form_recipe attributes[] action create/update/upsert/remove for data attributes. Supported field widgets include INPUT_FIELD, LABEL_FIELD, CHECK_BOX_FIELD, SPREADSHEET_DOCUMENT_FIELD, TEXT_DOCUMENT_FIELD, HTML_DOCUMENT_FIELD, PDF_DOCUMENT_FIELD, CHART_FIELD. Do not guess SpreadsheetDocument/ТабличныйДокумент as a form attribute data type; inspect type candidates or use a supported alias already accepted by EDT."
                },
                "validation_token": {
                  "type": "string",
                  "description": "Required unchanged token from edt_validate_request for this exact operations payload."
                }
              },
              "required": ["project", "form_fqn", "operations", "validation_token"]
            }
            """; //$NON-NLS-1$

    private final EdtFormService formService;
    private final MetadataRequestValidationService validationService;

    public MutateFormModelTool() {
        this(new EdtFormService(), new MetadataRequestValidationService());
    }

    MutateFormModelTool(EdtFormService formService, MetadataRequestValidationService validationService) {
        this.formService = formService;
        this.validationService = validationService;
    }

    @Override
    public String getDescription() {
        return "Меняет visual items существующей формы; attributes создавайте отдельно."; //$NON-NLS-1$
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
            String opId = LogSanitizer.newId("mutate-form"); //$NON-NLS-1$
            long startedAt = System.currentTimeMillis();
            LOG.info("[%s] START mutate_form_model", opId); //$NON-NLS-1$
            LOG.debug("[%s] Raw parameters: %s", opId, // $NON-NLS-1$
                    LogSanitizer.truncate(LogSanitizer.redactSecrets(String.valueOf(parameters)), 4000));
            try {
                String projectName = stringParam(parameters, "project"); //$NON-NLS-1$
                String formFqn = stringParam(parameters, "form_fqn"); //$NON-NLS-1$
                List<Map<String, Object>> operations = asListOfMaps(parameters.get("operations")); //$NON-NLS-1$
                String validationToken = stringParam(parameters, "validation_token"); //$NON-NLS-1$

                Map<String, Object> normalizedPayload = validationService.normalizeUpdateFormModelPayload(
                        projectName,
                        formFqn,
                        operations);
                Map<String, Object> validatedPayload = validationService.consumeToken(
                        validationToken,
                        ValidationOperation.MUTATE_FORM_MODEL,
                        projectName);
                if (!validatedPayload.equals(normalizedPayload)) {
                    LOG.warn("[%s] Input payload differs from validated payload, applying validated payload from token", opId); //$NON-NLS-1$
                }

                UpdateFormModelRequest request = new UpdateFormModelRequest(
                        projectName,
                        asRequiredString(validatedPayload, "form_fqn"), //$NON-NLS-1$
                        asListOfMaps(validatedPayload.get("operations"))); //$NON-NLS-1$
                UpdateFormModelResult result = formService.updateFormModel(request);
                LOG.info("[%s] SUCCESS in %s form=%s operations=%d", opId, //$NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        result.formFqn(),
                        Integer.valueOf(result.operationsApplied()));
                return ToolResult.success(result.formatForLlm());
            } catch (MetadataOperationException e) {
                LOG.warn("[%s] FAILED in %s: %s (%s)", opId, //$NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        e.getMessage(),
                        e.getCode());
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                LOG.error("[" + opId + "] mutate_form_model failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("Ошибка mutate_form_model: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private String stringParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value == null ? null : String.valueOf(value);
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asListOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }
}
