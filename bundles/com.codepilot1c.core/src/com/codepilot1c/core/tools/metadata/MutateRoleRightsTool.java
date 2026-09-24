package com.codepilot1c.core.tools.metadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.rights.EdtRoleRightsService;
import com.codepilot1c.core.edt.rights.EdtRoleRightsService.MutateResult;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.Gson;

/**
 * Mutates a 1C role's access rights (object rights + configuration/administrative rights + default
 * flags) via the EDT rights model and validation-token flow. RLS is out of scope (phase 2).
 */
@ToolMeta(name = "mutate_role_rights", category = "metadata", mutating = true,
        requiresValidationToken = true, tags = {"workspace", "edt"})
public class MutateRoleRightsTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(MutateRoleRightsTool.class);
    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {"type": "string", "description": "EDT project containing the role."},
                "role": {"type": "string", "description": "Role name or Role.<Name> FQN, e.g. БазаЗнанийПросмотр."},
                "operations": {
                  "type": "array",
                  "minItems": 1,
                  "items": {
                    "type": "object",
                    "properties": {
                      "op": {"type": "string", "enum": ["set_right", "set_config_right", "set_flags", "clear_object"], "description": "set_right: grant/revoke a right on a metadata object; set_config_right: a configuration/administrative right; set_flags: role default flags; clear_object: drop all explicit rights of one object."},
                      "object_fqn": {"type": "string", "description": "For set_right/clear_object: top object FQN like Catalog.Организации or Document.ЗаказПокупателя."},
                      "right": {"type": "string", "description": "Right name, English or Russian: Read/Чтение, Insert/Добавление, Update/Изменение, Delete/Удаление, View/Просмотр, Edit/Редактирование. For set_config_right: Administration, DataAdministration, ThinClient, WebClient, etc."},
                      "value": {"type": "string", "description": "set or allow to grant; unset or deny to revoke. Dependencies follow the EDT role editor: set also grants the rights it requires (Update needs Read); unset also revokes the rights that depend on it (unset Read revokes Update, View, Edit, ...), and a later set does NOT bring them back. details lists every right changed by such a cascade with its previous value, e.g. '+dep Update=UNSET (was SET)': to undo, set those rights explicitly."},
                      "set_for_new_objects": {"type": "boolean", "description": "set_flags: grant rights to newly added objects by default."},
                      "set_for_attributes_by_default": {"type": "boolean", "description": "set_flags: set rights for attributes and tabular sections by default."},
                      "independent_rights_of_child_objects": {"type": "boolean", "description": "set_flags: independent rights of subordinate objects."}
                    },
                    "required": ["op"],
                    "additionalProperties": true
                  },
                  "description": "Rights operations. Use inspect_role_rights first to see current values and exact object FQNs."
                },
                "validation_token": {"type": "string", "description": "Required unchanged token from edt_validate_request for this exact payload."}
              },
              "required": ["project", "role", "operations", "validation_token"]
            }
            """; //$NON-NLS-1$

    private final EdtRoleRightsService service;
    private final MetadataRequestValidationService validationService;

    public MutateRoleRightsTool() {
        this(new EdtRoleRightsService(), new MetadataRequestValidationService());
    }

    MutateRoleRightsTool(EdtRoleRightsService service, MetadataRequestValidationService validationService) {
        this.service = service;
        this.validationService = validationService;
    }

    @Override
    public String getDescription() {
        return "Меняет права роли 1С (по объектам, конфигурации, флаги) через модель прав EDT; RLS — отдельно."; //$NON-NLS-1$
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
            String opId = LogSanitizer.newId("mutate-role-rights"); //$NON-NLS-1$
            long startedAt = System.currentTimeMillis();
            LOG.info("[%s] START mutate_role_rights", opId); //$NON-NLS-1$
            try {
                String projectName = stringParam(parameters, "project"); //$NON-NLS-1$
                String role = stringParam(parameters, "role"); //$NON-NLS-1$
                List<Map<String, Object>> operations = asListOfMaps(parameters.get("operations")); //$NON-NLS-1$
                String validationToken = stringParam(parameters, "validation_token"); //$NON-NLS-1$

                Map<String, Object> normalizedPayload =
                        validationService.normalizeMutateRoleRightsPayload(projectName, role, operations);
                Map<String, Object> validatedPayload = validationService.consumeToken(
                        validationToken, ValidationOperation.MUTATE_ROLE_RIGHTS, projectName);
                if (!validatedPayload.equals(normalizedPayload)) {
                    LOG.warn("[%s] Input payload differs from validated payload, applying validated payload", opId); //$NON-NLS-1$
                }

                MutateResult result = service.mutateRoleRights(
                        projectName,
                        asRequiredString(validatedPayload, "role"), //$NON-NLS-1$
                        asListOfMaps(validatedPayload.get("operations"))); //$NON-NLS-1$
                LOG.info("[%s] SUCCESS in %s role=%s applied=%d", opId, //$NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        result.roleFqn(), Integer.valueOf(result.operationsApplied()));
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.CODE);
            } catch (MetadataOperationException e) {
                LOG.warn("[%s] FAILED: %s (%s)", opId, e.getMessage(), e.getCode()); //$NON-NLS-1$
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                LOG.error("[" + opId + "] mutate_role_rights failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("Ошибка mutate_role_rights: " + e.getMessage()); //$NON-NLS-1$
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
            throw new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_NAME,
                    "Required field missing in validated payload: " + key, false); //$NON-NLS-1$
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
