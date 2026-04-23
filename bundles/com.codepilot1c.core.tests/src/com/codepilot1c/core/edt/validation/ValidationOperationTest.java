package com.codepilot1c.core.edt.validation;

import static org.junit.Assert.assertEquals;

import java.util.Map;

import org.junit.Test;

public class ValidationOperationTest {

    @Test
    public void ensureModuleArtifactAliasIsAccepted() {
        assertEquals(ValidationOperation.ENSURE_MODULE_ARTIFACT,
                ValidationOperation.fromString("ensure_module_artifact")); //$NON-NLS-1$
    }

    @Test
    public void compositeExternalManageCommandResolvesToInternalOperation() {
        assertEquals(ValidationOperation.EXTERNAL_CREATE_REPORT,
                ValidationOperation.resolve("external_manage", Map.of("command", "create_report"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void compositeExtensionManageCommandResolvesToInternalOperation() {
        assertEquals(ValidationOperation.EXTENSION_SET_PROPERTY_STATE,
                ValidationOperation.resolve("extension_manage", Map.of("command", "set_state"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void compositeDcsManageCommandResolvesToInternalOperation() {
        assertEquals(ValidationOperation.DCS_UPSERT_QUERY_DATASET,
                ValidationOperation.resolve("dcs_manage", Map.of("command", "upsert_dataset"))); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
