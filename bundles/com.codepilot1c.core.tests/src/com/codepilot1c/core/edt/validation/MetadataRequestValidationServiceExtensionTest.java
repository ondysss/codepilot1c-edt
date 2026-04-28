package com.codepilot1c.core.edt.validation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

public class MetadataRequestValidationServiceExtensionTest {

    private final MetadataRequestValidationService service = new MetadataRequestValidationService();

    @Test
    public void normalizeExtensionAdoptPayloadUsesBaseProjectAsValidationProject() {
        Map<String, Object> payload = service.normalizeExtensionAdoptPayload(
                "DemoConfiguration", //$NON-NLS-1$
                "ExtensionDemo", //$NON-NLS-1$
                "DemoConfiguration", //$NON-NLS-1$
                "Catalog.Items", //$NON-NLS-1$
                Boolean.FALSE);

        assertEquals("DemoConfiguration", payload.get("project")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("DemoConfiguration", payload.get("base_project")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ExtensionDemo", payload.get("extension_project")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Catalog.Items", payload.get("source_object_fqn")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void normalizeExtensionAdoptPayloadExplainsProjectMismatch() {
        try {
            service.normalizeExtensionAdoptPayload(
                    "ExtensionDemo", //$NON-NLS-1$
                    "ExtensionDemo", //$NON-NLS-1$
                    "DemoConfiguration", //$NON-NLS-1$
                    "Catalog.Items", //$NON-NLS-1$
                    Boolean.FALSE);
            fail("Expected MetadataOperationException"); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.KNOWLEDGE_REQUIRED, e.getCode());
            assertTrue(e.getMessage().contains("project is the base EDT project")); //$NON-NLS-1$
            assertTrue(e.getMessage().contains("extension_project is the extension project")); //$NON-NLS-1$
        }
    }
}
