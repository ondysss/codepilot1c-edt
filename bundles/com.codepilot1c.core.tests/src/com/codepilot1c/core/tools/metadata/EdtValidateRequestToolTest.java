package com.codepilot1c.core.tools.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.edt.validation.ValidationRequest;
import com.codepilot1c.core.edt.validation.ValidationResult;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class EdtValidateRequestToolTest {

    @Test
    public void acceptsCompositeOperationAndPreservesRequestedOperationName() {
        StubValidationService validationService = new StubValidationService();
        EdtValidateRequestTool tool = new EdtValidateRequestTool(validationService);

        ToolResult result = tool.execute(Map.of(
                "project", "DemoConfiguration", //$NON-NLS-1$ //$NON-NLS-2$
                "operation", "external_manage", //$NON-NLS-1$ //$NON-NLS-2$
                "payload", Map.of(
                        "command", "create_report", //$NON-NLS-1$ //$NON-NLS-2$
                        "project", "DemoConfiguration", //$NON-NLS-1$ //$NON-NLS-2$
                        "external_project", "ExtReports", //$NON-NLS-1$ //$NON-NLS-2$
                        "name", "SalesReport" //$NON-NLS-1$ //$NON-NLS-2$
                ))).join();

        assertTrue(result.isSuccess());
        assertEquals(ValidationOperation.EXTERNAL_CREATE_REPORT, validationService.lastRequest.operation());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertEquals("external_manage", json.get("operation").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("token-1", json.get("validationToken").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void acceptsExtensionManageAdoptPayloadAndKeepsCompositeOperationName() {
        StubValidationService validationService = new StubValidationService();
        EdtValidateRequestTool tool = new EdtValidateRequestTool(validationService);

        ToolResult result = tool.execute(Map.of(
                "project", "DemoConfiguration", //$NON-NLS-1$ //$NON-NLS-2$
                "operation", "extension_manage", //$NON-NLS-1$ //$NON-NLS-2$
                "payload", Map.of(
                        "command", "adopt", //$NON-NLS-1$ //$NON-NLS-2$
                        "project", "DemoConfiguration", //$NON-NLS-1$ //$NON-NLS-2$
                        "base_project", "DemoConfiguration", //$NON-NLS-1$ //$NON-NLS-2$
                        "extension_project", "ExtensionDemo", //$NON-NLS-1$ //$NON-NLS-2$
                        "source_object_fqn", "Catalog.Items" //$NON-NLS-1$ //$NON-NLS-2$
                ))).join();

        assertTrue(result.isSuccess());
        assertEquals(ValidationOperation.EXTENSION_ADOPT_OBJECT, validationService.lastRequest.operation());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertEquals("extension_manage", json.get("operation").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("token-1", json.get("validationToken").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static final class StubValidationService extends MetadataRequestValidationService {
        private ValidationRequest lastRequest;

        @Override
        public ValidationResult validateAndIssueToken(ValidationRequest request) {
            lastRequest = request;
            return new ValidationResult(
                    true,
                    request.projectName(),
                    request.operation().getToolName(),
                    List.of("ok"), //$NON-NLS-1$
                    request.payload(),
                    "token-1", //$NON-NLS-1$
                    123L);
        }
    }
}
