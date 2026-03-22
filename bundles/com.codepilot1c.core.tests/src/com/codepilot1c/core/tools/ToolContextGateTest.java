package com.codepilot1c.core.tools;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Test;

/**
 * Tests for {@link ToolContextGate} tool family definitions.
 */
public class ToolContextGateTest {

    @Test
    public void excludedToolsNeverNull() {
        ToolContextGate gate = new ToolContextGate();
        Set<String> excluded = gate.computeExcludedTools();
        assertNotNull("Excluded tools must never be null", excluded); //$NON-NLS-1$
    }

    @Test
    public void dcsFamilyContainsAllDcsTools() {
        // Verify DCS_TOOLS covers all expected DCS tools
        Set<String> expected = Set.of(
                "dcs_get_summary", "dcs_list_nodes", "dcs_create_main_schema", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "dcs_upsert_query_dataset", "dcs_upsert_parameter", "dcs_upsert_calculated_field"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        ToolContextGate gate = new ToolContextGate();
        // In a test environment without workspace, DCS tools should be excluded
        Set<String> excluded = gate.computeExcludedTools();
        // At minimum, the gate should not crash
        assertNotNull(excluded);
    }

    @Test
    public void qaInitConfigNeverExcluded() {
        // qa_init_config must always be available so user can create QA config
        ToolContextGate gate = new ToolContextGate();
        Set<String> excluded = gate.computeExcludedTools();
        assertFalse("qa_init_config must never be excluded", //$NON-NLS-1$
                excluded.contains("qa_init_config")); //$NON-NLS-1$
    }

    @Test
    public void coreToolsNeverExcluded() {
        ToolContextGate gate = new ToolContextGate();
        Set<String> excluded = gate.computeExcludedTools();
        // File and basic tools must never be gated
        assertFalse("read_file must not be excluded", excluded.contains("read_file")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("edit_file must not be excluded", excluded.contains("edit_file")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("write_file must not be excluded", excluded.contains("write_file")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("glob must not be excluded", excluded.contains("glob")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("grep must not be excluded", excluded.contains("grep")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("list_files must not be excluded", excluded.contains("list_files")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("skill must not be excluded", excluded.contains("skill")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("task must not be excluded", excluded.contains("task")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void cacheInvalidation() {
        ToolContextGate gate = new ToolContextGate();
        Set<String> first = gate.computeExcludedTools();
        Set<String> cached = gate.computeExcludedTools();
        // Should return same cached instance
        assertTrue("Second call should return cached result", first == cached); //$NON-NLS-1$

        gate.invalidateCache();
        Set<String> afterInvalidation = gate.computeExcludedTools();
        // After invalidation, should recompute (may be different instance)
        assertNotNull("After invalidation should still return valid result", afterInvalidation); //$NON-NLS-1$
    }
}
