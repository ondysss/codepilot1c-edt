package com.codepilot1c.core.tools.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.edt.metadata.EdtMetadataService;
import com.codepilot1c.core.edt.metadata.EnsureModuleArtifactRequest;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.metadata.ModuleArtifactKind;
import com.codepilot1c.core.edt.metadata.ModuleArtifactResult;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.tools.ToolResult;

public class EnsureModuleArtifactToolTest {

    @Test
    public void acceptsCamelCaseAliasesFromLivePromptFlow() {
        StubMetadataService metadataService = new StubMetadataService();
        StubValidationService validationService = new StubValidationService();
        EnsureModuleArtifactTool tool = new EnsureModuleArtifactTool(metadataService, validationService);

        ToolResult result = tool.execute(Map.of(
                "project", "test", //$NON-NLS-1$ //$NON-NLS-2$
                "objectFqn", "Document.ПриходТоваров", //$NON-NLS-1$ //$NON-NLS-2$
                "moduleType", "object_module", //$NON-NLS-1$ //$NON-NLS-2$
                "createIfMissing", "false", //$NON-NLS-1$ //$NON-NLS-2$
                "initialContent", "Procedure BeforeWrite() EndProcedure", //$NON-NLS-1$ //$NON-NLS-2$
                "validationToken", "token-1" //$NON-NLS-1$ //$NON-NLS-2$
        )).join();

        assertTrue(result.isSuccess());
        assertEquals(ValidationOperation.ENSURE_MODULE_ARTIFACT, validationService.operation);
        assertEquals("test", validationService.projectName); //$NON-NLS-1$
        assertEquals("Document.ПриходТоваров", validationService.normalizedPayload.get("object_fqn")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("OBJECT", validationService.normalizedPayload.get("module_kind")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.FALSE, validationService.normalizedPayload.get("create_if_missing")); //$NON-NLS-1$
        assertEquals("Document.ПриходТоваров", metadataService.lastRequest.objectFqn()); //$NON-NLS-1$
        assertEquals(ModuleArtifactKind.OBJECT, metadataService.lastRequest.moduleKind());
        assertEquals(false, metadataService.lastRequest.createIfMissing());
        assertEquals("Procedure BeforeWrite() EndProcedure", metadataService.lastRequest.initialContent()); //$NON-NLS-1$
        assertTrue(result.getContent().contains("/tmp/Documents/ПриходТоваров/ObjectModule.bsl")); //$NON-NLS-1$
    }

    private static final class StubMetadataService extends EdtMetadataService {
        private EnsureModuleArtifactRequest lastRequest;

        @Override
        public ModuleArtifactResult ensureModuleArtifact(EnsureModuleArtifactRequest request) {
            lastRequest = request;
            return new ModuleArtifactResult(
                    request.projectName(),
                    request.objectFqn(),
                    request.moduleKind(),
                    "/tmp/Documents/ПриходТоваров/ObjectModule.bsl", //$NON-NLS-1$
                    false);
        }
    }

    private static final class StubValidationService extends MetadataRequestValidationService {
        private ValidationOperation operation;
        private String projectName;
        private Map<String, Object> normalizedPayload;

        @Override
        public Map<String, Object> consumeToken(String token, ValidationOperation operation, String projectName) {
            if (!"token-1".equals(token)) { //$NON-NLS-1$
                throw new MetadataOperationException(
                        MetadataOperationCode.KNOWLEDGE_REQUIRED,
                        "unexpected token", false); //$NON-NLS-1$
            }
            this.operation = operation;
            this.projectName = projectName;
            this.normalizedPayload = Map.of(
                    "project", projectName, //$NON-NLS-1$
                    "object_fqn", "Document.ПриходТоваров", //$NON-NLS-1$ //$NON-NLS-2$
                    "module_kind", "OBJECT", //$NON-NLS-1$ //$NON-NLS-2$
                    "create_if_missing", Boolean.FALSE, //$NON-NLS-1$
                    "initial_content", "Procedure BeforeWrite() EndProcedure" //$NON-NLS-1$ //$NON-NLS-2$
            );
            return normalizedPayload;
        }
    }
}
