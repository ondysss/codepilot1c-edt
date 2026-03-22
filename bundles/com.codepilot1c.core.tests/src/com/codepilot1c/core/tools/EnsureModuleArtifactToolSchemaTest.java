package com.codepilot1c.core.tools;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.codepilot1c.core.tools.metadata.EnsureModuleArtifactTool;

public class EnsureModuleArtifactToolSchemaTest {

    @Test
    public void schemaRequiresValidationToken() {
        String schema = new EnsureModuleArtifactTool().getParameterSchema();
        assertTrue(schema.contains("\"validation_token\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"required\": [\"project\", \"object_fqn\", \"validation_token\"]")); //$NON-NLS-1$
    }
}
