package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.tools.qa.QaPrepareFormContextTool;
import com.codepilot1c.core.edt.forms.CreateFormRequest;
import com.codepilot1c.core.edt.forms.CreateFormResult;
import com.codepilot1c.core.edt.forms.EdtFormService;
import com.codepilot1c.core.edt.forms.FormUsage;
import com.codepilot1c.core.edt.forms.InspectFormLayoutRequest;
import com.codepilot1c.core.edt.forms.InspectFormLayoutResult;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.edt.validation.ValidationRequest;
import com.codepilot1c.core.edt.validation.ValidationResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class QaPrepareFormContextToolTest {

    @Test
    public void usesExistingFormWithoutCreation() {
        StubFormService formService = new StubFormService();
        formService.inspectResult = inspectResult("test", "Catalog.Номенклатура.Form.ФормаСписка"); //$NON-NLS-1$ //$NON-NLS-2$
        StubValidationService validationService = new StubValidationService();
        QaPrepareFormContextTool tool = new QaPrepareFormContextTool(formService, validationService);

        ToolResult result = tool.execute(Map.of(
                "project", "test", //$NON-NLS-1$ //$NON-NLS-2$
                "owner_fqn", "Catalog.Номенклатура", //$NON-NLS-1$ //$NON-NLS-2$
                "usage", "LIST" //$NON-NLS-1$ //$NON-NLS-2$
        )).join();

        assertTrue(result.isSuccess());
        assertEquals(ToolResult.ToolResultType.SEARCH_RESULTS, result.getType());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertEquals("ready", json.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(json.get("created").getAsBoolean()); //$NON-NLS-1$
        assertEquals("Catalog.Номенклатура.Form.ФормаСписка", json.get("form_fqn").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, formService.inspectCalls);
        assertEquals(0, formService.createCalls);
        assertEquals(0, validationService.validateCalls);
    }

    @Test
    public void createsMissingFormAndReinspects() {
        StubFormService formService = new StubFormService();
        formService.inspectException = new MetadataOperationException(
                MetadataOperationCode.METADATA_NOT_FOUND,
                "Form not found", false); //$NON-NLS-1$
        formService.inspectResult = inspectResult("test", "Document.ПоступлениеТоваров.Form.ФормаСписка"); //$NON-NLS-1$ //$NON-NLS-2$
        formService.createResult = new CreateFormResult(
                "Document.ПоступлениеТоваров", //$NON-NLS-1$
                "Document.ПоступлениеТоваров.Form.ФормаСписка", //$NON-NLS-1$
                FormUsage.LIST,
                true,
                true,
                "/tmp/Form.form", //$NON-NLS-1$
                null,
                "ok"); //$NON-NLS-1$
        StubValidationService validationService = new StubValidationService();
        QaPrepareFormContextTool tool = new QaPrepareFormContextTool(formService, validationService);

        ToolResult result = tool.execute(Map.of(
                "project", "test", //$NON-NLS-1$ //$NON-NLS-2$
                "owner_fqn", "Document.ПоступлениеТоваров", //$NON-NLS-1$ //$NON-NLS-2$
                "usage", "LIST" //$NON-NLS-1$ //$NON-NLS-2$
        )).join();

        assertTrue(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertEquals("created", json.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.get("created").getAsBoolean()); //$NON-NLS-1$
        assertNotNull(json.getAsJsonObject("creation")); //$NON-NLS-1$
        assertEquals(2, formService.inspectCalls);
        assertEquals(1, formService.createCalls);
        assertEquals(1, validationService.validateCalls);
        assertEquals(1, validationService.consumeCalls);
        assertEquals(ValidationOperation.CREATE_FORM, validationService.lastValidationRequest.operation());
        assertEquals("Document.ПоступлениеТоваров", formService.lastCreateRequest.ownerFqn()); //$NON-NLS-1$
        assertEquals("ФормаСписка", formService.lastCreateRequest.name()); //$NON-NLS-1$
    }

    private static InspectFormLayoutResult inspectResult(String project, String formFqn) {
        return new InspectFormLayoutResult(
                project,
                formFqn,
                "ФормаСписка", //$NON-NLS-1$
                Map.of(),
                1,
                false,
                List.of(new InspectFormLayoutResult.FormItemNode(
                        1,
                        null,
                        0,
                        "/Форма", //$NON-NLS-1$
                        "Форма", //$NON-NLS-1$
                        "Form", //$NON-NLS-1$
                        Map.of(),
                        Boolean.TRUE,
                        Boolean.TRUE,
                        Boolean.FALSE,
                        null,
                        null,
                        Map.of(),
                        List.of())));
    }

    private static final class StubFormService extends EdtFormService {
        private int inspectCalls;
        private int createCalls;
        private MetadataOperationException inspectException;
        private InspectFormLayoutResult inspectResult;
        private CreateFormResult createResult;
        private CreateFormRequest lastCreateRequest;

        @Override
        public InspectFormLayoutResult inspectFormLayout(InspectFormLayoutRequest request) {
            inspectCalls++;
            if (inspectException != null) {
                MetadataOperationException exception = inspectException;
                inspectException = null;
                throw exception;
            }
            return inspectResult;
        }

        @Override
        public CreateFormResult createForm(CreateFormRequest request) {
            createCalls++;
            lastCreateRequest = request;
            return createResult;
        }
    }

    private static final class StubValidationService extends MetadataRequestValidationService {
        private int validateCalls;
        private int consumeCalls;
        private ValidationRequest lastValidationRequest;

        @Override
        public ValidationResult validateAndIssueToken(ValidationRequest request) {
            validateCalls++;
            lastValidationRequest = request;
            return new ValidationResult(
                    true,
                    request.projectName(),
                    request.operation().getToolName(),
                    List.of("ok"), //$NON-NLS-1$
                    request.payload(),
                    "token-1", //$NON-NLS-1$
                    System.currentTimeMillis() + 60_000L);
        }

        @Override
        public Map<String, Object> consumeToken(String token, ValidationOperation operation, String projectName) {
            consumeCalls++;
            return lastValidationRequest.payload();
        }
    }
}
