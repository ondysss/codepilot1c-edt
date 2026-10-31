package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.*;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;

/** Can run unchanged against the previous plugin to prove the missing contract. */
public class FormParameterDispatchTest {
    @Test public void dispatcherAcceptsFormalParameters() throws Exception {
        var service = new EdtMetadataService(new EdtMetadataGateway(), new com.codepilot1c.core.edt.forms.EventHandlerTargetResolver(item -> List.of()));
        Form form = FormFactory.eINSTANCE.createForm();
        Method dispatch = EdtMetadataService.class.getDeclaredMethod("applyFormModelOperations",
                Form.class, List.class, List.class);
        dispatch.setAccessible(true);
        dispatch.invoke(service, form, List.of(Map.of("op", "add_parameter", "name", "Input")), new ArrayList<>());
        assertEquals(1, form.getParameters().size());
    }

    @Test public void inspectionReportsFormalParametersWithoutScalarProperties() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        var parameter = FormFactory.eINSTANCE.createFormParameter();
        parameter.setName("Input"); form.getParameters().add(parameter);
        Method inspect = EdtMetadataService.class.getDeclaredMethod("collectFormRootProperties",
                Form.class, boolean.class, boolean.class);
        inspect.setAccessible(true);
        Map<?, ?> data = (Map<?, ?>) inspect.invoke(new EdtMetadataService(new EdtMetadataGateway(), new com.codepilot1c.core.edt.forms.EventHandlerTargetResolver(item -> List.of())), form, false, false);
        assertEquals(1, ((List<?>) data.get("parameters")).size());
    }
}
