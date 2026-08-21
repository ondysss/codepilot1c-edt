package com.codepilot1c.core.agent.prompts;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.Test;

/**
 * Regression tests for GSD execute-prompt content.
 *
 * <p>Guards against re-introducing the incorrect instruction that told the
 * model to transition to VERIFYING when a task is blocked — the VERIFYING
 * entry guard requires all tasks DONE, so a blocked unfinished task would
 * be rejected. The correct behaviour is to keep the task non-DONE, report
 * the blocker via evidence, and let the user decide.</p>
 *
 * <p>Documented rollbacks match the domain transition matrix, including
 * reasoned recovery from a failed shipment.</p>
 */
public class GsdExecutePromptTest {

    @Test
    public void finalParityPassReplacesOverrideToolGuidanceAndStaleInstructions() {
        String overridden = """
                # Provider override
                Use write_file to change source.

                ## Инструменты
                write_file, mcp_docs_lookup.

                ## Формат
                Report evidence.
                """;
        String prompt = AgentPromptTemplates.enforceGsdToolParity(
                overridden,
                Set.of("read_file", "mcp_docs_lookup"), //$NON-NLS-1$ //$NON-NLS-2$
                List.of("read_file", "write_file", "mcp_docs_lookup")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertFalse(prompt.contains("write_file")); //$NON-NLS-1$
        assertTrue(prompt.contains("mcp_docs_lookup, read_file.")); //$NON-NLS-1$
        assertTrue(prompt.contains("## Формат")); //$NON-NLS-1$
        assertTrue(prompt.lastIndexOf("## Инструменты") //$NON-NLS-1$
                > prompt.indexOf("## Формат")); //$NON-NLS-1$
    }

    @Test
    public void executePromptDoesNotInstructVerifyTransitionForBlockedTask() {
        String prompt = AgentPromptTemplates.buildGsdExecutePrompt();

        // The old incorrect text instructed transitioning to VERIFYING for blocked tasks.
        assertFalse(
                "Execute prompt must NOT instruct VERIFYING transition for blocked tasks " //$NON-NLS-1$
                        + "(VERIFYING entry guard requires all DONE)", //$NON-NLS-1$
                prompt.contains("переходи в VERIFYING через gsd_transition для оценки")); //$NON-NLS-1$
    }

    @Test
    public void executePromptInstructsKeepTaskNonDoneOnBlocker() {
        String prompt = AgentPromptTemplates.buildGsdExecutePrompt();

        assertTrue(
                "Execute prompt must instruct keeping the task in current (non-DONE) status on blocker", //$NON-NLS-1$
                prompt.contains("оставь задачу в текущем статусе")); //$NON-NLS-1$
        assertTrue(
                "Execute prompt must instruct recording the blocker via evidence", //$NON-NLS-1$
                prompt.contains("gsd_record_evidence")); //$NON-NLS-1$
    }

    @Test
    public void executePromptDocumentsCompleteRollbackMatrix() {
        String prompt = AgentPromptTemplates.buildGsdExecutePrompt();

        assertTrue(prompt.contains("VERIFYING->EXECUTING")); //$NON-NLS-1$
        assertTrue(prompt.contains("SHIPPING->VERIFYING")); //$NON-NLS-1$
        assertTrue(prompt.contains("SHIPPING->EXECUTING")); //$NON-NLS-1$
        assertFalse(prompt.contains("Единственный допустимый rollback")); //$NON-NLS-1$
    }

    @Test
    public void shipPromptExplainsFailedShipmentRecovery() {
        String prompt = AgentPromptTemplates.buildGsdShipPrompt();

        assertTrue(prompt.contains("FAILED shipment")); //$NON-NLS-1$
        assertTrue(prompt.contains("SHIPPING->VERIFYING")); //$NON-NLS-1$
        assertTrue(prompt.contains("SHIPPING->EXECUTING")); //$NON-NLS-1$
        assertTrue(prompt.contains("reason")); //$NON-NLS-1$
        assertTrue(prompt.contains("model-facing инструмента замены цикла нет")); //$NON-NLS-1$
    }

    @Test
    public void executePromptWarnsVerifyingRequiresAllDone() {
        String prompt = AgentPromptTemplates.buildGsdExecutePrompt();

        assertTrue(
                "Execute prompt must warn that VERIFYING transition requires all tasks DONE", //$NON-NLS-1$
                prompt.contains("all DONE")); //$NON-NLS-1$
    }

    @Test
    public void executePromptForbidsExecutingToPlanning() {
        String prompt = AgentPromptTemplates.buildGsdExecutePrompt();

        assertTrue(
                "Execute prompt must state EXECUTING->PLANNING is forbidden", //$NON-NLS-1$
                prompt.contains("EXECUTING->PLANNING")); //$NON-NLS-1$
    }
}
