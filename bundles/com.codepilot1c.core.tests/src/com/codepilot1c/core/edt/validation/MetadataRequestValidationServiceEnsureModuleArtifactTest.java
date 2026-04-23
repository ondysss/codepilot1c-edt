package com.codepilot1c.core.edt.validation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

public class MetadataRequestValidationServiceEnsureModuleArtifactTest {

    private final MetadataRequestValidationService service = new MetadataRequestValidationService();

    @Test
    public void normalizeEnsureModuleArtifactPayloadCanonicalizesAliases() {
        Map<String, Object> payload = service.normalizeEnsureModuleArtifactPayload(
                "Demo", //$NON-NLS-1$
                "Catalog.Customers", //$NON-NLS-1$
                "object_module", //$NON-NLS-1$
                Boolean.FALSE,
                "Procedure Test() EndProcedure"); //$NON-NLS-1$

        assertEquals("Demo", payload.get("project")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Catalog.Customers", payload.get("object_fqn")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("OBJECT", payload.get("module_kind")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(Boolean.TRUE.equals(payload.get("create_if_missing"))); //$NON-NLS-1$
        assertEquals("Procedure Test() EndProcedure", payload.get("initial_content")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void normalizeEnsureModuleArtifactPayloadDefaultsCreateIfMissingToTrue() {
        Map<String, Object> payload = service.normalizeEnsureModuleArtifactPayload(
                "Demo", //$NON-NLS-1$
                "Catalog.Customers", //$NON-NLS-1$
                null,
                null,
                null);

        assertEquals("AUTO", payload.get("module_kind")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(Boolean.TRUE.equals(payload.get("create_if_missing"))); //$NON-NLS-1$
    }

    @Test
    public void normalizeEnsureModuleArtifactPayloadAcceptsCamelCaseAliases() {
        Map<String, Object> payload = service.normalizeEnsureModuleArtifactPayload(
                "Demo", //$NON-NLS-1$
                Map.of(
                        "objectFqn", "Document.ПриходТоваров", //$NON-NLS-1$ //$NON-NLS-2$
                        "moduleType", "object_module", //$NON-NLS-1$ //$NON-NLS-2$
                        "createIfMissing", "false", //$NON-NLS-1$ //$NON-NLS-2$
                        "initialContent", "Procedure BeforeWrite() EndProcedure" //$NON-NLS-1$ //$NON-NLS-2$
                ));

        assertEquals("Demo", payload.get("project")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Document.ПриходТоваров", payload.get("object_fqn")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("OBJECT", payload.get("module_kind")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(Boolean.TRUE.equals(payload.get("create_if_missing"))); //$NON-NLS-1$
        assertEquals("Procedure BeforeWrite() EndProcedure", payload.get("initial_content")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void normalizeEnsureModuleArtifactPayloadCanonicalizesModuleSuffixInObjectFqn() {
        Map<String, Object> payload = service.normalizeEnsureModuleArtifactPayload(
                "Demo", //$NON-NLS-1$
                Map.of("objectFqn", "Document/ПриходТоваров/ObjectModule")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("Document.ПриходТоваров", payload.get("object_fqn")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("OBJECT", payload.get("module_kind")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(Boolean.TRUE.equals(payload.get("create_if_missing"))); //$NON-NLS-1$
    }
}
