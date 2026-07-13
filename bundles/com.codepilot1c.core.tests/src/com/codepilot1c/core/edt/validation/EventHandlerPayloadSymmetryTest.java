package com.codepilot1c.core.edt.validation;

import static org.junit.Assert.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Proves OPS-04: the three new event-handler op payloads round-trip byte-identically
 * through {@code normalizeUpdateFormModelPayload} on both the validate call site and
 * the consume call site (Map.equals holds), with zero per-op field renaming.
 */
public class EventHandlerPayloadSymmetryTest {

    private final MetadataRequestValidationService validationService = new MetadataRequestValidationService();

    @Test
    public void addEventHandlerPayloadRoundTripsSymmetrically() {
        assertSymmetricRoundTrip(operation(
                "add_event_handler", "OnChange", "MyOnChange", "form")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void setEventHandlerPayloadRoundTripsSymmetrically() {
        assertSymmetricRoundTrip(operation(
                "set_event_handler", "OnOpen", "MyOnOpen", "form")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void removeEventHandlerPayloadRoundTripsSymmetrically() {
        assertSymmetricRoundTrip(operation(
                "remove_event_handler", "OnChange", "MyOnChange", "form")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    private void assertSymmetricRoundTrip(Map<String, Object> operationEntry) {
        String project = "Proj"; //$NON-NLS-1$
        String formFqn = "Catalog.X.Form.Y"; //$NON-NLS-1$
        List<Map<String, Object>> operations = List.of(operationEntry);

        // Simulates the validate call site (edt_validate_request -> validateAndIssueToken).
        Map<String, Object> validatePayload =
                validationService.normalizeUpdateFormModelPayload(project, formFqn, operations);
        // Simulates the consume call site (mutate_form_model -> doExecute), called again
        // with the identical inputs.
        Map<String, Object> consumePayload =
                validationService.normalizeUpdateFormModelPayload(project, formFqn, operations);

        assertEquals(validatePayload, consumePayload);
        // Verbatim pass-through: no field renamed, no field dropped, no field added.
        assertEquals(operations, validatePayload.get("operations")); //$NON-NLS-1$
        assertEquals(operations, consumePayload.get("operations")); //$NON-NLS-1$
    }

    private static Map<String, Object> operation(String op, String event, String handlerName, String target) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("op", op); //$NON-NLS-1$
        entry.put("event", event); //$NON-NLS-1$
        entry.put("handler_name", handlerName); //$NON-NLS-1$
        entry.put("target", target); //$NON-NLS-1$
        return entry;
    }
}
