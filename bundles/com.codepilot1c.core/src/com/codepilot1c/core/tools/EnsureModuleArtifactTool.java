package com.codepilot1c.core.tools;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.metadata.EdtMetadataService;
import com.codepilot1c.core.edt.metadata.EnsureModuleArtifactRequest;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.metadata.ModuleArtifactKind;
import com.codepilot1c.core.edt.metadata.ModuleArtifactResult;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Ensures module artifact (*.bsl) exists for a metadata object.
 */
public class EnsureModuleArtifactTool implements ITool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(EnsureModuleArtifactTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {
                  "type": "string",
                  "description": "Имя проекта EDT"
                },
                "object_fqn": {
                  "type": "string",
                  "description": "FQN объекта метаданных, например Document.ПриходнаяНакладная"
                },
                "module_kind": {
                  "type": "string",
                  "description": "Тип модуля: auto|object|manager|module"
                },
                "create_if_missing": {
                  "type": "boolean",
                  "description": "Создать файл модуля, если отсутствует (по умолчанию true)"
                },
                "initial_content": {
                  "type": "string",
                  "description": "Начальное содержимое файла при создании (опционально)"
                }
              },
              "required": ["project", "object_fqn"]
            }
            """; //$NON-NLS-1$

    private final EdtMetadataService metadataService;

    public EnsureModuleArtifactTool() {
        this(new EdtMetadataService());
    }

    EnsureModuleArtifactTool(EdtMetadataService metadataService) {
        this.metadataService = metadataService;
    }

    @Override
    public String getName() {
        return "ensure_module_artifact"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Гарантирует наличие файла модуля (*.bsl) для объекта метаданных и возвращает его путь."; //$NON-NLS-1$
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
            String opId = LogSanitizer.newId("ensure-module"); //$NON-NLS-1$
            long startedAt = System.currentTimeMillis();
            LOG.info("[%s] START ensure_module_artifact", opId); //$NON-NLS-1$
            LOG.debug("[%s] Raw parameters: %s", opId, //$NON-NLS-1$
                    LogSanitizer.truncate(LogSanitizer.redactSecrets(String.valueOf(parameters)), 4000));
            try {
                String projectName = getString(parameters, "project"); //$NON-NLS-1$
                String objectFqn = getString(parameters, "object_fqn"); //$NON-NLS-1$
                ModuleArtifactKind moduleKind = ModuleArtifactKind.fromString(getString(parameters, "module_kind")); //$NON-NLS-1$
                boolean createIfMissing = !Boolean.FALSE.equals(parameters.get("create_if_missing")); //$NON-NLS-1$
                String initialContent = getString(parameters, "initial_content"); //$NON-NLS-1$

                EnsureModuleArtifactRequest request = new EnsureModuleArtifactRequest(
                        projectName,
                        objectFqn,
                        moduleKind,
                        createIfMissing,
                        initialContent);
                ModuleArtifactResult result = metadataService.ensureModuleArtifact(request);
                LOG.info("[%s] SUCCESS in %s path=%s", opId, //$NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        result.modulePath());
                return ToolResult.success(result.formatForLlm());
            } catch (MetadataOperationException e) {
                LOG.warn("[%s] FAILED in %s: %s (%s)", opId, //$NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        e.getMessage(),
                        e.getCode());
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                LOG.error("[" + opId + "] ensure_module_artifact failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("Ошибка ensure_module_artifact: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private String getString(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
