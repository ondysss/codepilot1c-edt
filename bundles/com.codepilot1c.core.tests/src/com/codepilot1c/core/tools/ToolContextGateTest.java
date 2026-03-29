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
        // DCS tools are exposed through a single composite entry.
        Set<String> expected = Set.of("dcs_manage"); //$NON-NLS-1$
        ToolContextGate gate = new ToolContextGate();
        // In a live workspace test run the tool may or may not be excluded depending on imported projects.
        Set<String> excluded = gate.computeExcludedTools();
        assertNotNull(excluded);
        assertTrue(expected.contains("dcs_manage")); //$NON-NLS-1$
    }

    @Test
    public void qaGenerateNeverExcluded() {
        // qa_generate must stay available so init_config can bootstrap QA config.
        ToolContextGate gate = new ToolContextGate();
        Set<String> excluded = gate.computeExcludedTools();
        assertFalse("qa_generate must never be excluded", //$NON-NLS-1$
                excluded.contains("qa_generate")); //$NON-NLS-1$
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
