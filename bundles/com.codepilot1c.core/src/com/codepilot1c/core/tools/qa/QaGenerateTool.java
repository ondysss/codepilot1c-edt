package com.codepilot1c.core.tools.qa;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;

/**
 * Composite mutating QA generation tool that dispatches to
 * domain-specific QA config/feature generation tools.
 *
 * <p>Commands:</p>
 * <ul>
 *   <li>{@code init_config} — create initial qa-config.json</li>
 *   <li>{@code migrate_config} — migrate/normalize qa-config.json</li>
 *   <li>{@code compile_feature} — compile structured QA plan into feature file</li>
 * </ul>
 *
 * <p>Replaces individual qa_init_config, qa_migrate_config, qa_compile_feature tools.</p>
 */
@ToolMeta(name = "qa_generate", category = "file",
        surfaceCategory = "qa",
        mutating = true,
        tags = {"workspace"})
public class QaGenerateTool extends AbstractTool {

    private static final Set<String> ALL_COMMANDS = Set.of(
            "init_config", "migrate_config", "compile_feature"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "command": {
                  "type": "string",
                  "description": "Command: init_config|migrate_config|compile_feature",
                  "enum": ["init_config", "migrate_config", "compile_feature"]
                }
              },
              "required": ["command"],
              "additionalProperties": true
            }
            """; //$NON-NLS-1$

    private final ITool initConfig;
    private final ITool migrateConfig;
    private final ITool compileFeature;

    public QaGenerateTool() {
        this(new QaInitConfigTool(),
             new QaMigrateConfigTool(),
             new QaCompileFeatureTool());
    }

    QaGenerateTool(ITool initConfig, ITool migrateConfig, ITool compileFeature) {
        this.initConfig = initConfig;
        this.migrateConfig = migrateConfig;
        this.compileFeature = compileFeature;
    }

    @Override
    public String getDescription() {
        return "QA generation: init/migrate config, compile feature files."; //$NON-NLS-1$
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
        Map<String, Object> p = params.getRaw();
        String command = p.get("command") != null ? String.valueOf(p.get("command")) : null; //$NON-NLS-1$
        if (command == null || !ALL_COMMANDS.contains(command)) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure("Unknown command: " + command + //$NON-NLS-1$
                            ". Use one of: " + String.join(", ", ALL_COMMANDS))); //$NON-NLS-1$ //$NON-NLS-2$
        }

        ITool delegate = switch (command) {
            case "init_config" -> initConfig; //$NON-NLS-1$
            case "migrate_config" -> migrateConfig; //$NON-NLS-1$
            case "compile_feature" -> compileFeature; //$NON-NLS-1$
            default -> null;
        };

        if (delegate == null) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure("Unknown command: " + command)); //$NON-NLS-1$
        }

        return delegate.execute(p);
    }
}
