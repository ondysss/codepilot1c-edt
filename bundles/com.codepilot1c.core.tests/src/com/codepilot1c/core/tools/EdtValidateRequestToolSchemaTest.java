package com.codepilot1c.core.tools;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EdtValidateRequestToolSchemaTest {

    @Test
    public void schemaListsEnsureModuleArtifactOperation() {
        String schema = new EdtValidateRequestTool().getParameterSchema();
        assertTrue(schema.contains("\"ensure_module_artifact\"")); //$NON-NLS-1$
    }
}
