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
 * Composite read-only QA inspection tool that dispatches to
 * domain-specific QA query tools.
 *
 * <p>Commands:</p>
 * <ul>
 *   <li>{@code explain_config} — explain QA configuration</li>
 *   <li>{@code status} — QA runtime/config status</li>
 *   <li>{@code steps_search} — search available QA steps</li>
 * </ul>
 *
 * <p>Replaces individual qa_explain_config, qa_status, qa_steps_search tools.</p>
 */
@ToolMeta(name = "qa_inspect", category = "diagnostics",
        surfaceCategory = "qa",
        tags = {"read-only", "workspace"})
public class QaInspectTool extends AbstractTool {

    private static final Set<String> ALL_COMMANDS = Set.of(
            "explain_config", "status", "steps_search"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "command": {
                  "type": "string",
                  "description": "Command: explain_config|status|steps_search",
                  "enum": ["explain_config", "status", "steps_search"]
                }
              },
              "required": ["command"],
              "additionalProperties": true
            }
            """; //$NON-NLS-1$

    private final ITool explainConfig;
    private final ITool status;
    private final ITool stepsSearch;

    public QaInspectTool() {
        this(new QaExplainConfigTool(),
             new QaStatusTool(),
             new QaStepsSearchTool());
    }

    QaInspectTool(ITool explainConfig, ITool status, ITool stepsSearch) {
        this.explainConfig = explainConfig;
        this.status = status;
        this.stepsSearch = stepsSearch;
    }

    @Override
    public String getDescription() {
        return "QA inspection: explain config, check status, search steps."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public boolean requiresConfirmation() {
        return false;
    }

    @Override
    public boolean isDestructive() {
        return false;
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
            case "explain_config" -> explainConfig; //$NON-NLS-1$
            case "status" -> status; //$NON-NLS-1$
            case "steps_search" -> stepsSearch; //$NON-NLS-1$
            default -> null;
        };

        if (delegate == null) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure("Unknown command: " + command)); //$NON-NLS-1$
        }

        return delegate.execute(p);
    }
}
