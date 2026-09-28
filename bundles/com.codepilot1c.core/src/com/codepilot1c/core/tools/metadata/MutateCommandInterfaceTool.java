package com.codepilot1c.core.tools.metadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.cmi.EdtCommandInterfaceService;
import com.codepilot1c.core.edt.cmi.EdtCommandInterfaceService.MutateResult;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
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
 * Changes the command interface ({@code CommandInterface.cmi}) of a subsystem or of the configuration
 * root through the EDT model and the validation-token flow: {@code prune_orphans} removes visibility entries
 * that name no command, {@code delete} removes the whole interface of a subsystem hidden from the command interface.
 */
@ToolMeta(name = "mutate_command_interface", category = "metadata", mutating = true,
        requiresValidationToken = true, tags = {"workspace", "edt"})
public class MutateCommandInterfaceTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(MutateCommandInterfaceTool.class);
    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {"type": "string", "description": "EDT project containing the owner."},
                "owner_fqn": {"type": "string", "description": "Owner of the command interface: Configuration, Subsystem.<Name> or a nested Subsystem.<Name>.Subsystem.<Child> (the Russian EDT form is accepted)."},
                "operations": {
                  "type": "array",
                  "minItems": 1,
                  "items": {
                    "type": "object",
                    "properties": {
                      "op": {"type": "string", "enum": ["prune_orphans", "delete"], "description": "prune_orphans: remove visibility entries that name no command (EDT error 'Команда для настройки видимости должна быть указана'); entries with a command, platform commands (0:<uuid>) and unresolved references, placement and order are not touched. delete: remove the whole command interface of a subsystem that is not shown in the command interface (includeInCommandInterface=false) - it never reaches the screen; refused for a shown subsystem and for the configuration root. Idempotent."}
                    },
                    "required": ["op"]
                  },
                  "description": "Command interface operations. Use inspect_command_interface first to see the entries."
                },
                "validation_token": {"type": "string", "description": "Required unchanged token from edt_validate_request for this exact payload."}
              },
              "required": ["project", "owner_fqn", "operations", "validation_token"]
            }
            """; //$NON-NLS-1$

    private final EdtCommandInterfaceService service;
    private final MetadataRequestValidationService validationService;

    public MutateCommandInterfaceTool() {
        this(new EdtCommandInterfaceService(), new MetadataRequestValidationService());
    }

    MutateCommandInterfaceTool(EdtCommandInterfaceService service, MetadataRequestValidationService validationService) {
        this.service = service;
        this.validationService = validationService;
    }

    @Override
    public String getDescription() {
        return "Меняет командный интерфейс подсистемы или конфигурации через модель EDT: " //$NON-NLS-1$
                + "prune_orphans убирает записи видимости без команды, delete - весь интерфейс скрытой подсистемы."; //$NON-NLS-1$
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
            String opId = LogSanitizer.newId("mutate-cmi"); //$NON-NLS-1$
            long startedAt = System.currentTimeMillis();
            LOG.info("[%s] START mutate_command_interface", opId); //$NON-NLS-1$
            try {
                String projectName = stringParam(parameters, "project"); //$NON-NLS-1$
                String ownerFqn = stringParam(parameters, "owner_fqn"); //$NON-NLS-1$
                List<Map<String, Object>> operations = asListOfMaps(parameters.get("operations")); //$NON-NLS-1$
                String validationToken = stringParam(parameters, "validation_token"); //$NON-NLS-1$

                Map<String, Object> normalizedPayload =
                        validationService.normalizeMutateCommandInterfacePayload(projectName, ownerFqn, operations);
                Map<String, Object> validatedPayload = validationService.consumeToken(
                        validationToken, ValidationOperation.MUTATE_COMMAND_INTERFACE, projectName);
                if (!validatedPayload.equals(normalizedPayload)) {
                    LOG.warn("[%s] Input payload differs from validated payload, applying validated payload", opId); //$NON-NLS-1$
                }

                MutateResult result = service.mutate(
                        projectName,
                        asRequiredString(validatedPayload, "owner_fqn"), //$NON-NLS-1$
                        asListOfMaps(validatedPayload.get("operations"))); //$NON-NLS-1$
                LOG.info("[%s] SUCCESS in %s owner=%s removed=%d", opId, //$NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        result.ownerFqn(), Integer.valueOf(result.removed()));
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.CODE);
            } catch (MetadataOperationException e) {
                LOG.warn("[%s] FAILED: %s (%s)", opId, e.getMessage(), e.getCode()); //$NON-NLS-1$
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                LOG.error("[" + opId + "] mutate_command_interface failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("Ошибка mutate_command_interface: " + e.getMessage()); //$NON-NLS-1$
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
