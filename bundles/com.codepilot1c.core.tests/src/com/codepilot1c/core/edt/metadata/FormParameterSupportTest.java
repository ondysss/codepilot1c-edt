package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.*;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.mcore.McoreFactory;

public class FormParameterSupportTest {
    @Test public void createsUpdatesAndRemovesWithoutTouchingAttributes() {
        Form form = FormFactory.eINSTANCE.createForm();
        FormParameterSupport.apply(form, Map.of("op", "add_parameter", "name", "Диалог",
                "keyParameter", true, "comment", "Saved conversation"), (p, t) -> fail());
        var parameter = form.getParameters().get(0);
        assertEquals("Диалог", parameter.getName());
        assertTrue(parameter.isKeyParameter());
        assertTrue(form.getAttributes().isEmpty());
        FormParameterSupport.apply(form, Map.of("op", "set_parameter", "name", "диалог",
                "set", Map.of("key_parameter", false, "comment", "")), (p, t) -> fail());
        assertFalse(parameter.isKeyParameter());
        assertEquals("", parameter.getComment());
        FormParameterSupport.apply(form, Map.of("op", "remove_parameter", "name", "Диалог"), (p, t) -> fail());
        assertTrue(form.getParameters().isEmpty());
    }

    @Test public void failedTypeResolutionDoesNotLeaveAnAddedParameter() {
        Form form = FormFactory.eINSTANCE.createForm();
        assertThrows(IllegalArgumentException.class, () -> FormParameterSupport.apply(form,
                Map.of("op", "add_parameter", "name", "Input", "type", "Missing"),
                (p, t) -> { assertSame(form, p.eContainer()); throw new IllegalArgumentException(); }));
        assertTrue(form.getParameters().isEmpty());
    }

    @Test public void failedUpdatePreservesTypeAndOtherProperties() {
        Form form = FormFactory.eINSTANCE.createForm();
        FormParameterSupport.apply(form, Map.of("op", "add_parameter", "name", "Input",
                "keyParameter", true, "comment", "original"), (p, t) -> fail());
        var parameter = form.getParameters().get(0);
        var oldType = McoreFactory.eINSTANCE.createTypeDescription();
        parameter.setValueType(oldType);
        assertThrows(IllegalArgumentException.class, () -> FormParameterSupport.apply(form,
                Map.of("op", "set_parameter", "name", "Input", "set", Map.of("type", "Missing",
                        "comment", "changed", "keyParameter", false)),
                (p, t) -> { p.setValueType(McoreFactory.eINSTANCE.createTypeDescription());
                    throw new IllegalArgumentException(); }));
        assertSame(oldType, parameter.getValueType());
        assertTrue(parameter.isKeyParameter());
        assertEquals("original", parameter.getComment());
    }

    @Test public void rejectsDuplicateMissingAndInvalidProperties() {
        Form form = FormFactory.eINSTANCE.createForm();
        FormParameterSupport.apply(form, Map.of("op", "add_parameter", "name", "Input"), (p, t) -> fail());
        List<Map<String, Object>> bad = List.of(
                Map.of("op", "add_parameter", "name", "input"),
                Map.of("op", "set_parameter", "name", "Missing", "set", Map.of("comment", "x")),
                Map.of("op", "remove_parameter", "name", "Missing"),
                Map.of("op", "remove_parameter", "name", "Input", "comment", "ignored"),
                Map.of("op", "add_parameter", "name", "Empty", "type", List.of()),
                Map.of("op", "set_parameter", "name", "Input", "set", Map.of()),
                Map.of("op", "set_parameter", "name", "Input", "set", Map.of("keyParameter", "false")),
                Map.of("op", "set_parameter", "name", "Input", "set", Map.of("name", "Renamed")),
                Map.of("op", "set_parameter", "name", "Input", "set", Map.of("unknown", true)),
                Map.of("op", "add_parameter", "name", "bad name"),
                Map.of("op", "set_parameter", "name", "Input", "set", Map.of("keyParameter", true, "key_parameter", false)));
        for (var request : bad) {
            assertThrows(request.toString(), MetadataOperationException.class,
                    () -> FormParameterSupport.apply(form, request, (p, t) -> fail()));
            assertEquals(1, form.getParameters().size());
            assertEquals("Input", form.getParameters().get(0).getName());
        }
    }

    @Test public void inspectionIncludesDeclaredTypesAndQualifiers() {
        Form form = FormFactory.eINSTANCE.createForm();
        FormParameterSupport.apply(form, Map.of("op", "add_parameter", "name", "Input", "type", "String(36)"),
                (p, t) -> {
                    var description = McoreFactory.eINSTANCE.createTypeDescription();
                    var type = McoreFactory.eINSTANCE.createType(); type.setName("String");
                    description.getTypes().add(type);
                    var qualifiers = McoreFactory.eINSTANCE.createStringQualifiers(); qualifiers.setLength(36);
                    description.setStringQualifiers(qualifiers); p.setValueType(description);
                });
        var data = FormParameterSupport.describe(form).get(0);
        assertEquals(List.of("String"), data.get("types"));
        assertEquals(36, data.get("stringLength"));
    }

    /** Regression against the actual dispatcher, not just the new helper. */
    @Test public void nativeDispatcherAcceptsParameterOperations() throws Exception {
        var service = new EdtMetadataService(new EdtMetadataGateway(), new com.codepilot1c.core.edt.forms.EventHandlerTargetResolver(item -> List.of()));
        Form form = FormFactory.eINSTANCE.createForm();
        Method dispatch = EdtMetadataService.class.getDeclaredMethod("applyFormModelOperations",
                Form.class, List.class, List.class);
        dispatch.setAccessible(true);
        dispatch.invoke(service, form, List.of(Map.of("op", "add_parameter", "name", "Input")), new ArrayList<>());
        assertEquals(1, form.getParameters().size());
        Method inspect = EdtMetadataService.class.getDeclaredMethod("collectFormRootProperties",
                Form.class, boolean.class, boolean.class);
        inspect.setAccessible(true);
        Map<?, ?> data = (Map<?, ?>) inspect.invoke(service, form, false, false);
        assertEquals(1, ((List<?>) data.get("parameters")).size());
    }
}
