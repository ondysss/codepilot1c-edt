package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
import com._1c.g5.v8.dt.form.model.Table;
import com._1c.g5.v8.dt.mcore.Event;
import com._1c.g5.v8.dt.mcore.McoreFactory;

import com.codepilot1c.core.edt.forms.EventHandlerCatalog;
import com.codepilot1c.core.edt.forms.EventHandlerTargetResolver;

/**
 * Covers OPS-01/OPS-02/OPS-03/EVT-04: the {@code add_event_handler}/{@code set_event_handler}/
 * {@code remove_event_handler} wiring in {@link EdtMetadataService#applyFormModelOperations}.
 *
 * <p>Runs entirely against a fake {@link EventHandlerCatalog} injected via the package-visible
 * {@code EdtMetadataService(EdtMetadataGateway, EventHandlerTargetResolver)} constructor — no
 * live EDT/BM/OSGi dependency, matching the 06-01 seam design.</p>
 */
public class EventHandlerWiringTest {

    private static final String EVENT_ON_OPEN_EN = "OnOpen"; //$NON-NLS-1$
    private static final String EVENT_ON_OPEN_RU = "ПриОткрытии"; //$NON-NLS-1$
    private static final String EVENT_ON_CHANGE_EN = "OnChange"; //$NON-NLS-1$
    private static final String EVENT_ON_CHANGE_RU = "ПриИзменении"; //$NON-NLS-1$

    private Event onOpenEvent;
    private Event onChangeEvent;
    private EdtMetadataService service;

    @Before
    public void setUp() {
        onOpenEvent = createEvent(EVENT_ON_OPEN_EN, EVENT_ON_OPEN_RU);
        onChangeEvent = createEvent(EVENT_ON_CHANGE_EN, EVENT_ON_CHANGE_RU);
        EventHandlerCatalog fakeCatalog = item -> List.of(onOpenEvent, onChangeEvent);
        EventHandlerTargetResolver resolver = new EventHandlerTargetResolver(fakeCatalog);
        service = new EdtMetadataService(new EdtMetadataGateway(), resolver);
    }

    @Test
    public void addEventHandlerOnFormWiresHandlerWithSuppliedName() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();

        List<String> summaries = applyFormModelOperations(form, List.of(
                opAddEventHandler("form", null, EVENT_ON_OPEN_EN, "MyOnOpenHandler"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(1, form.getHandlers().size());
        EventHandler handler = form.getHandlers().get(0);
        assertEquals(onOpenEvent, handler.getEvent());
        assertEquals("MyOnOpenHandler", handler.getName()); //$NON-NLS-1$
        assertEquals(1, summaries.size());
        assertTrue(summaries.get(0).contains("add_event_handler")); //$NON-NLS-1$
    }

    @Test
    public void addEventHandlerTwiceWithSameTargetAndEventUpserts() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();

        applyFormModelOperations(form, List.of(
                opAddEventHandler("form", null, EVENT_ON_OPEN_EN, "FirstName"))); //$NON-NLS-1$ //$NON-NLS-2$
        applyFormModelOperations(form, List.of(
                opAddEventHandler("form", null, EVENT_ON_OPEN_EN, "SecondName"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(1, form.getHandlers().size());
        assertEquals("SecondName", form.getHandlers().get(0).getName()); //$NON-NLS-1$
    }

    @Test
    public void setEventHandlerRoutesToSameUpsertImplementationAsAdd() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();

        applyFormModelOperations(form, List.of(
                opAddEventHandler("form", null, EVENT_ON_OPEN_EN, "AddName"))); //$NON-NLS-1$ //$NON-NLS-2$
        applyFormModelOperations(form, List.of(
                opSetEventHandler("form", null, EVENT_ON_OPEN_EN, "SetName"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(1, form.getHandlers().size());
        assertEquals("SetName", form.getHandlers().get(0).getName()); //$NON-NLS-1$
    }

    @Test
    public void addEventHandlerOnFormFieldWiresHandlerByItemId() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        FormField field = FormFactory.eINSTANCE.createFormField();
        field.setId(1);
        field.setName("MyField"); //$NON-NLS-1$
        form.getItems().add(field);

        applyFormModelOperations(form, List.of(
                opAddEventHandlerByItemId(1, EVENT_ON_CHANGE_EN, "MyFieldOnChange"))); //$NON-NLS-1$

        assertEquals(1, field.getHandlers().size());
        assertEquals(onChangeEvent, field.getHandlers().get(0).getEvent());
    }

    @Test
    public void addEventHandlerOnTableWiresHandlerByItemId() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        Table table = FormFactory.eINSTANCE.createTable();
        table.setId(1);
        table.setName("MyTable"); //$NON-NLS-1$
        form.getItems().add(table);

        applyFormModelOperations(form, List.of(
                opAddEventHandlerByItemId(1, EVENT_ON_CHANGE_EN, "MyTableOnChange"))); //$NON-NLS-1$

        assertEquals(1, table.getHandlers().size());
        assertEquals(onChangeEvent, table.getHandlers().get(0).getEvent());
    }

    @Test
    public void addEventHandlerOnButtonThrowsInvalidMetadataChangeRedirectingToAddCommand() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        Button button = FormFactory.eINSTANCE.createButton();
        button.setId(1);
        button.setName("MyButton"); //$NON-NLS-1$
        form.getItems().add(button);

        try {
            applyFormModelOperations(form, List.of(
                    opAddEventHandlerByItemId(1, EVENT_ON_CHANGE_EN, "MyButtonOnChange"))); //$NON-NLS-1$
            fail("expected MetadataOperationException"); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.INVALID_METADATA_CHANGE, e.getCode());
            assertTrue(e.getMessage().contains("add_command")); //$NON-NLS-1$
        }
    }

    @Test
    public void addEventHandlerWithUnknownEventThrowsMetadataNotFoundWithAllowedList() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();

        try {
            applyFormModelOperations(form, List.of(
                    opAddEventHandler("form", null, "Nonexistent", "SomeName"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("expected MetadataOperationException"); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.METADATA_NOT_FOUND, e.getCode());
            assertTrue(e.getMessage().contains(EVENT_ON_OPEN_EN));
            assertTrue(e.getMessage().contains(EVENT_ON_CHANGE_EN));
        }
    }

    @Test
    public void addEventHandlerWithBlankHandlerNameProducesDeterministicDefaultName() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();

        applyFormModelOperations(form, List.of(
                opAddEventHandler("form", null, EVENT_ON_OPEN_EN, null))); //$NON-NLS-1$

        assertEquals(1, form.getHandlers().size());
        String name = form.getHandlers().get(0).getName();
        assertNotNull(name);
        assertFalse(name.isBlank());
        assertTrue(MetadataNameValidator.isValidName(name));
        assertTrue(name.contains(EVENT_ON_OPEN_EN));
    }

    @Test
    public void addEventHandlerWithInvalidHandlerNameThrowsInvalidMetadataName() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();

        try {
            applyFormModelOperations(form, List.of(
                    opAddEventHandler("form", null, EVENT_ON_OPEN_EN, "9bad name"))); //$NON-NLS-1$ //$NON-NLS-2$
            fail("expected MetadataOperationException"); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.INVALID_METADATA_NAME, e.getCode());
        }
    }

    @Test
    public void removeEventHandlerRemovesMatchingHandlerAndNothingElse() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        applyFormModelOperations(form, List.of(
                opAddEventHandler("form", null, EVENT_ON_OPEN_EN, "MyOnOpenHandler"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, form.getHandlers().size());

        applyFormModelOperations(form, List.of(
                opRemoveEventHandler("form", null, EVENT_ON_OPEN_EN))); //$NON-NLS-1$

        assertEquals(0, form.getHandlers().size());
    }

    @Test
    public void removeEventHandlerOnUnwiredEventIsNoOp() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();

        applyFormModelOperations(form, List.of(
                opRemoveEventHandler("form", null, EVENT_ON_OPEN_EN))); //$NON-NLS-1$

        assertEquals(0, form.getHandlers().size());
    }

    private static Event createEvent(String nameEn, String nameRu) {
        Event event = McoreFactory.eINSTANCE.createEvent();
        event.setName(nameEn);
        event.setNameRu(nameRu);
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

    private static Map<String, Object> opSetEventHandler(String target, Integer itemId, String event, String handlerName) {
        Map<String, Object> operation = opAddEventHandler(target, itemId, event, handlerName);
        operation.put("op", "set_event_handler"); //$NON-NLS-1$ //$NON-NLS-2$
        return operation;
    }

    private static Map<String, Object> opRemoveEventHandler(String target, Integer itemId, String event) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("op", "remove_event_handler"); //$NON-NLS-1$ //$NON-NLS-2$
        if (target != null) {
            operation.put("target", target); //$NON-NLS-1$
        }
        if (itemId != null) {
            operation.put("item_id", itemId); //$NON-NLS-1$
        }
        operation.put("event", event); //$NON-NLS-1$
        return operation;
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
}
