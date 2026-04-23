package com.codepilot1c.core.agent.graph;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.codepilot1c.core.agent.AgentConfig;
import com.codepilot1c.core.tools.ToolResult;

public class ToolGraphRouterEnsureModuleArtifactTest {

    @Test
    public void metadataGraphRequiresValidationBeforeEnsureAndAllowsDiagnosticsAfterEnsure() {
        ToolGraphRouter router = ToolGraphRouter.createDefault();
        router.initialize("graph=metadata", AgentConfig.defaults()); //$NON-NLS-1$

        ToolGraphToolFilter beforeValidation = router.buildToolFilter();
        assertFalse(beforeValidation.allows("ensure_module_artifact")); //$NON-NLS-1$

        router.onToolResult("edt_validate_request", ToolResult.success("ok")); //$NON-NLS-1$ //$NON-NLS-2$
        ToolGraphToolFilter afterValidation = router.buildToolFilter();
        assertTrue(afterValidation.allows("ensure_module_artifact")); //$NON-NLS-1$

        router.onToolResult("ensure_module_artifact", ToolResult.success("ok")); //$NON-NLS-1$ //$NON-NLS-2$
        ToolGraphToolFilter afterEnsure = router.buildToolFilter();
        assertTrue(afterEnsure.allows("get_diagnostics")); //$NON-NLS-1$
        assertTrue(afterEnsure.allows("edit_file")); //$NON-NLS-1$
    }

    @Test
    public void bslGraphHidesEnsureUntilValidationAndThenAllowsDiagnosticsFlow() {
        ToolGraphRouter router = ToolGraphRouter.createDefault();
        router.initialize("graph=bsl", AgentConfig.defaults()); //$NON-NLS-1$

        ToolGraphToolFilter beforeValidation = router.buildToolFilter();
        assertFalse(beforeValidation.allows("ensure_module_artifact")); //$NON-NLS-1$

        router.onToolResult("edt_validate_request", ToolResult.success("ok")); //$NON-NLS-1$ //$NON-NLS-2$
        ToolGraphToolFilter afterValidation = router.buildToolFilter();
        assertTrue(afterValidation.allows("ensure_module_artifact")); //$NON-NLS-1$

        router.onToolResult("ensure_module_artifact", ToolResult.success("ok")); //$NON-NLS-1$ //$NON-NLS-2$
        ToolGraphToolFilter afterEnsure = router.buildToolFilter();
        assertTrue(afterEnsure.allows("get_diagnostics")); //$NON-NLS-1$
        assertTrue(afterEnsure.allows("edit_file")); //$NON-NLS-1$
    }
}
