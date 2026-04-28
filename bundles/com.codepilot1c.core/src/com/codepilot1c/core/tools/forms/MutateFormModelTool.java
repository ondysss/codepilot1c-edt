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
                        "enum": ["set_form_props", "add_group", "add_field", "add_command", "add_button", "set_item", "remove_item", "move_item"],
                        "description": "Операция. set_form_props=свойства формы; add_group/add_field/add_command/add_button=добавить элемент; set_item=изменить любые свойства существующего элемента (включая смену типа виджета); remove_item=удалить; move_item=переместить."
                      }
                    },
                    "required": ["op"],
                    "additionalProperties": true
                  },
                  "description": "Список операций над моделью формы. Форматы: set_form_props{set:{...}}; add_group{parent_item_id,name,type:USUAL_GROUP|PAGES|PAGE|COLUMN_GROUP|BUTTON_GROUP|COMMAND_BAR|POPUP}; add_field{parent_item_id,name,data_path,type:INPUT_FIELD|LABEL_FIELD|CHECK_BOX_FIELD|RADIO_BUTTON_FIELD|PICTURE_FIELD|CALENDAR_FIELD|PERIOD_FIELD|PROGRESS_BAR_FIELD|TRACK_BAR_FIELD|SPREADSHEET_DOCUMENT_FIELD|TEXT_DOCUMENT_FIELD|HTML_DOCUMENT_FIELD|FORMATTED_DOCUMENT_FIELD|PDF_DOCUMENT_FIELD|CHART_FIELD|GANTT_CHART_FIELD|DENDROGRAM_FIELD|GEOGRAPHICAL_SCHEMA_FIELD|GRAPHICAL_SCHEMA_FIELD|PLANNER_FIELD}; add_command{name,action(имя процедуры-обработчика)}; add_button{name,command_name,parent_item_id}; set_item{id,set:{...}} — set принимает EMF feature имена case-insensitive: type (меняет виджет существующего FormField/FormGroup на другой литерал ManagedFormFieldType/ManagedFormGroupType), title, visible, enabled, readOnly, dataPath, userVisible, valueType (для FormAttribute через set_form_props.attributes); remove_item{id}; move_item{id,parent_item_id,index}. Примеры смены типа: {op:set_item,id:MyField,set:{type:LABEL_FIELD}}; {op:set_item,id:MyGroup,set:{type:PAGES}}."
                },
                "validation_token": {
                  "type": "string",
                  "description": "Одноразовый токен из edt_validate_request for this form-mutation request."
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
        return "Точечные изменения модели существующей управляемой формы через EDT BM API: свойства формы, группы/страницы, поля, команды, кнопки. set_item меняет тип виджета поля и группы."; //$NON-NLS-1$
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
