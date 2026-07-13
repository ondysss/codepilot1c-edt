package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.Button;
import com._1c.g5.v8.dt.form.model.EventHandler;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.mcore.Event;
import com._1c.g5.v8.dt.mcore.McoreFactory;

import com.codepilot1c.core.edt.forms.EventHandlerCatalog;
import com.codepilot1c.core.edt.forms.EventHandlerTargetResolver;
import com.codepilot1c.core.edt.forms.InspectFormLayoutRequest;
import com.codepilot1c.core.edt.forms.InspectFormLayoutResult;

/**
 * Covers INSP-01: {@code inspect_form_layout} surfaces existing event handlers as
 * {@code {event, handlerName}} on both the form root (via {@code formProperties}) and each
 * {@code EventHandlerContainer} {@link InspectFormLayoutResult.FormItemNode}.
 *
 * <p>Invokes the private {@code collectFormRootProperties}/{@code collectFormItemNodes} directly
 * via reflection (same package, mirrors the {@code applyFormModelOperations} reflection convention
 * already used by {@link EventHandlerWiringTest}) — no live EDT/BM/OSGi dependency, since both
 * collectors only read EMF getters plus the request/state value objects.</p>
 */
public class EventHandlerInspectTest {

    private static final String EVENT_ON_OPEN_EN = "OnOpen"; //$NON-NLS-1$
    private static final String EVENT_ON_CHANGE_EN = "OnChange"; //$NON-NLS-1$

    private EdtMetadataService service;

    @Before
    public void setUp() {
        EventHandlerCatalog fakeCatalog = item -> List.of();
        EventHandlerTargetResolver resolver = new EventHandlerTargetResolver(fakeCatalog);
        service = new EdtMetadataService(new EdtMetadataGateway(), resolver);
    }

    @Test
    public void formRootEventHandlersSurfacesFormLevelHandler() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        attachHandler(form, EVENT_ON_OPEN_EN, "FormOnOpen"); //$NON-NLS-1$

        Map<String, Object> formProperties = collectFormRootProperties(form, false, false);

        @SuppressWarnings("unchecked")
        List<InspectFormLayoutResult.EventHandlerInfo> eventHandlers =
                (List<InspectFormLayoutResult.EventHandlerInfo>) formProperties.get("eventHandlers"); //$NON-NLS-1$
        assertEquals(1, eventHandlers.size());
        assertEquals(new InspectFormLayoutResult.EventHandlerInfo(EVENT_ON_OPEN_EN, "FormOnOpen"), //$NON-NLS-1$
                eventHandlers.get(0));
    }

    @Test
    public void fieldFormItemNodeEventHandlersSurfacesFieldHandler() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        FormField field = FormFactory.eINSTANCE.createFormField();
        field.setId(1);
        field.setName("MyField"); //$NON-NLS-1$
        attachHandler(field, EVENT_ON_CHANGE_EN, "FieldOnChange"); //$NON-NLS-1$
        form.getItems().add(field);

        List<InspectFormLayoutResult.FormItemNode> nodes = collectFormItemNodes(form);

        assertEquals(1, nodes.size());
        InspectFormLayoutResult.FormItemNode fieldNode = nodes.get(0);
        assertEquals(1, fieldNode.eventHandlers().size());
        assertEquals(new InspectFormLayoutResult.EventHandlerInfo(EVENT_ON_CHANGE_EN, "FieldOnChange"), //$NON-NLS-1$
                fieldNode.eventHandlers().get(0));
    }

    @Test
    public void nonContainerButtonNodeYieldsEmptyEventHandlers() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        Button button = FormFactory.eINSTANCE.createButton();
        button.setId(1);
        button.setName("MyButton"); //$NON-NLS-1$
        form.getItems().add(button);

        List<InspectFormLayoutResult.FormItemNode> nodes = collectFormItemNodes(form);

        assertEquals(1, nodes.size());
        assertTrue(nodes.get(0).eventHandlers().isEmpty());
    }

    @Test
    public void wiredHandlersAreVisibleOnBothFormRootAndFieldAfterAddEventHandler() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        FormField field = FormFactory.eINSTANCE.createFormField();
        field.setId(1);
        field.setName("MyField"); //$NON-NLS-1$
        form.getItems().add(field);

        Event onOpenEvent = createEvent(EVENT_ON_OPEN_EN);
        Event onChangeEvent = createEvent(EVENT_ON_CHANGE_EN);
        EventHandlerCatalog fakeCatalog = item -> List.of(onOpenEvent, onChangeEvent);
        EventHandlerTargetResolver resolver = new EventHandlerTargetResolver(fakeCatalog);
        service = new EdtMetadataService(new EdtMetadataGateway(), resolver);

        applyFormModelOperations(form, List.of(
                opAddEventHandler("form", null, EVENT_ON_OPEN_EN, "FormOnOpen"))); //$NON-NLS-1$ //$NON-NLS-2$
        applyFormModelOperations(form, List.of(
                opAddEventHandlerByItemId(1, EVENT_ON_CHANGE_EN, "FieldOnChange"))); //$NON-NLS-1$

        Map<String, Object> formProperties = collectFormRootProperties(form, false, false);
        @SuppressWarnings("unchecked")
        List<InspectFormLayoutResult.EventHandlerInfo> rootHandlers =
                (List<InspectFormLayoutResult.EventHandlerInfo>) formProperties.get("eventHandlers"); //$NON-NLS-1$
        assertEquals(1, rootHandlers.size());
        assertEquals("FormOnOpen", rootHandlers.get(0).handlerName()); //$NON-NLS-1$

        List<InspectFormLayoutResult.FormItemNode> nodes = collectFormItemNodes(form);
        assertEquals(1, nodes.size());
        assertEquals(1, nodes.get(0).eventHandlers().size());
        assertEquals("FieldOnChange", nodes.get(0).eventHandlers().get(0).handlerName()); //$NON-NLS-1$
    }

    private static void attachHandler(com._1c.g5.v8.dt.form.model.EventHandlerContainer container, String eventName,
            String handlerName) {
        EventHandler handler = FormFactory.eINSTANCE.createEventHandler();
        handler.setEvent(createEvent(eventName));
        handler.setName(handlerName);
        container.getHandlers().add(handler);
    }

    private static Event createEvent(String nameEn) {
        Event event = McoreFactory.eINSTANCE.createEvent();
        event.setName(nameEn);
        return event;
    }

    private static Map<String, Object> opAddEventHandler(String target, Integer itemId, String event, String handlerName) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("op", "add_event_handler"); //$NON-NLS-1$ //$NON-NLS-2$
        if (target != null) {
            operation.put("target", target); //$NON-NLS-1$
        }
        if (itemId != null) {
            operation.put("item_id", itemId); //$NON-NLS-1$
        }
        operation.put("event", event); //$NON-NLS-1$
        if (handlerName != null) {
            operation.put("handler_name", handlerName); //$NON-NLS-1$
        }
        return operation;
    }

    private static Map<String, Object> opAddEventHandlerByItemId(int itemId, String event, String handlerName) {
        return opAddEventHandler(null, Integer.valueOf(itemId), event, handlerName);
    }

    @SuppressWarnings("unchecked")
    private List<String> applyFormModelOperations(Form formModel, List<Map<String, Object>> operations)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        Method method = EdtMetadataService.class.getDeclaredMethod(
                "applyFormModelOperations", Form.class, List.class); //$NON-NLS-1$
        method.setAccessible(true);
        try {
            return (List<String>) method.invoke(service, formModel, operations);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> collectFormRootProperties(Form formModel, boolean includeProperties, boolean includeTitles)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        Method method = EdtMetadataService.class.getDeclaredMethod(
                "collectFormRootProperties", Form.class, boolean.class, boolean.class); //$NON-NLS-1$
        method.setAccessible(true);
        try {
            return (Map<String, Object>) method.invoke(service, formModel, includeProperties, includeTitles);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private List<InspectFormLayoutResult.FormItemNode> collectFormItemNodes(Form formModel)
            throws ReflectiveOperationException {
        // includeInvisible=true: freshly-built EMF fixture items default their unset
        // "visible" EBoolean feature to false, which is unrelated to what this test
        // exercises (event-handler surfacing, not visibility filtering).
        InspectFormLayoutRequest request = new InspectFormLayoutRequest(
                "TestProject", "Form.Test", false, false, true, 0, 0); //$NON-NLS-1$ //$NON-NLS-2$

        Class<?> stateClass = Class.forName(
                "com.codepilot1c.core.edt.metadata.EdtMetadataService$FormInspectState"); //$NON-NLS-1$
        Constructor<?> stateConstructor = stateClass.getDeclaredConstructor(int.class);
        stateConstructor.setAccessible(true);
        Object state = stateConstructor.newInstance(request.effectiveMaxItems());

        Method method = EdtMetadataService.class.getDeclaredMethod(
                "collectFormItemNodes", //$NON-NLS-1$
                com._1c.g5.v8.dt.form.model.FormItemContainer.class,
                Integer.class,
                String.class,
                int.class,
                InspectFormLayoutRequest.class,
                stateClass);
        method.setAccessible(true);
        try {
            return (List<InspectFormLayoutResult.FormItemNode>) method.invoke(
                    service, formModel, null, "/Form", 0, request, state); //$NON-NLS-1$
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
    }
}
