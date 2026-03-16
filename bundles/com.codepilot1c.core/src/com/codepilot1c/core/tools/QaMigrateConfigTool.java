package com.codepilot1c.core.tools;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;

import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.qa.QaConfig;
import com.codepilot1c.core.qa.QaConfigMigration;
import com.codepilot1c.core.qa.QaPaths;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class QaMigrateConfigTool implements ITool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(QaMigrateConfigTool.class);
    private static final String DEFAULT_CONFIG_PATH = "tests/qa/qa-config.json"; //$NON-NLS-1$

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "config_path": {
                  "type": "string",
                  "description": "Path to qa-config.json (workspace-relative or absolute)"
                },
                "project_name": {
                  "type": "string",
                  "description": "Fallback EDT project name for migration"
                },
                "dry_run": {
                  "type": "boolean",
                  "description": "Analyze and preview migration without writing file"
                },
                "create_backup": {
                  "type": "boolean",
                  "description": "Create .bak copy before writing migrated config (default: true)"
                }
              }
            }
            """; //$NON-NLS-1$

    @Override
    public String getName() {
        return "qa_migrate_config"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Нормализует legacy qa-config.json, сохраняет совместимые поля и добавляет недостающие настройки Vanessa Automation."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("qa-migrate"); //$NON-NLS-1$
            LOG.info("[%s] START qa_migrate_config", opId); //$NON-NLS-1$
            try {
                File workspaceRoot = getWorkspaceRoot();
                String configPath = parameters == null ? null : (String) parameters.get("config_path"); //$NON-NLS-1$
                String projectName = parameters == null ? null : (String) parameters.get("project_name"); //$NON-NLS-1$
                boolean dryRun = parameters != null && Boolean.TRUE.equals(parameters.get("dry_run")); //$NON-NLS-1$
                boolean createBackup = parameters == null || !Boolean.FALSE.equals(parameters.get("create_backup")); //$NON-NLS-1$

                File configFile = QaPaths.resolveConfigFile(configPath, workspaceRoot, DEFAULT_CONFIG_PATH);
                if (configFile != null && workspaceRoot != null
                        && !QaPaths.isWithinWorkspace(workspaceRoot, configFile)) {
                    return ToolResult.failure("QA_MIGRATE_CONFIG_ERROR: config_path must be within workspace"); //$NON-NLS-1$
                }

                QaConfig existing = configFile.exists() ? QaConfig.load(configFile) : new QaConfig();
                String fallbackProjectName = resolveProjectName(projectName);
                QaConfigMigration.MigrationReport report = QaConfigMigration.analyze(existing,
                        fallbackProjectName,
                        existing.edt != null && Boolean.TRUE.equals(existing.edt.use_runtime),
                        existing.test_runner == null || !Boolean.FALSE.equals(existing.test_runner.use_test_manager));

                File backupFile = null;
                if (!dryRun) {
                    if (createBackup && configFile.exists()) {
                        backupFile = createBackup(configFile);
                    }
                    report.migratedConfig().save(configFile);
                }

                JsonObject result = new JsonObject();
                result.addProperty("op_id", opId); //$NON-NLS-1$
                result.addProperty("status", dryRun ? "analysis" : "migrated"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                result.addProperty("timestamp", Instant.now().toString()); //$NON-NLS-1$
                result.addProperty("config_path", configFile.getAbsolutePath()); //$NON-NLS-1$
                result.addProperty("legacy_detected", report.legacyDetected()); //$NON-NLS-1$
                result.addProperty("incomplete", report.incomplete()); //$NON-NLS-1$
                result.addProperty("changed", report.changed()); //$NON-NLS-1$
                if (backupFile != null) {
                    result.addProperty("backup_path", backupFile.getAbsolutePath()); //$NON-NLS-1$
                }
                result.add("warnings", toArray(report.warnings())); //$NON-NLS-1$
                result.add("applied_changes", toArray(report.appliedChanges())); //$NON-NLS-1$
                result.add("migrated_config", new GsonBuilder().create().toJsonTree(report.migratedConfig())); //$NON-NLS-1$
                return ToolResult.success(new GsonBuilder().setPrettyPrinting().create().toJson(result),
                        ToolResult.ToolResultType.CODE);
            } catch (Exception e) {
                LOG.error("[" + opId + "] qa_migrate_config failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("QA_MIGRATE_CONFIG_ERROR: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    @Override
    public boolean isDestructive() {
        return true;
    }

    private static JsonArray toArray(Iterable<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static File createBackup(File configFile) throws IOException {
        String suffix = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(java.time.LocalDateTime.now()); //$NON-NLS-1$
        File backup = new File(configFile.getParentFile(), configFile.getName() + "." + suffix + ".bak"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.copy(configFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return backup;
    }

    private static File getWorkspaceRoot() {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        return root.getLocation().toFile();
    }

    private static String resolveProjectName(String projectNameParam) {
        if (projectNameParam != null && !projectNameParam.isBlank()) {
            return projectNameParam;
        }
        var projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        if (projects != null) {
            for (var project : projects) {
                if (project != null && project.isOpen()) {
                    return project.getName();
                }
            }
        }
        return null;
    }
}
