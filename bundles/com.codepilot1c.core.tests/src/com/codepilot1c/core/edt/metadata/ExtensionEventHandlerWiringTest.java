package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.mcore.Event;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.metadata.mdclass.CommonForm;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;

import com.codepilot1c.core.edt.forms.EventHandlerCatalog;
import com.codepilot1c.core.edt.forms.EventHandlerTargetResolver;

/**
 * Covers EXT-01/EXT-02/EXT-03: the extension-adoption branch added to
 * {@link EdtMetadataService#applyFormModelOperations} on top of Phase 6/7's base-config
 * event-handler wiring.
 *
 * <p>Runs entirely against a fake {@link EventHandlerCatalog} injected via the
 * package-visible {@code EdtMetadataService(EdtMetadataGateway, EventHandlerTargetResolver)}
 * constructor — no live EDT/BM/OSGi dependency, matching {@code EventHandlerWiringTest}'s
 * seam design (06-01/08-02).</p>
 *
 * <p>Task 1 (Wave 0 scaffold) establishes and proves the ADOPTED/NATIVE fixture pair with
 * ZERO live BM transaction, per D-RESEARCH "Wave 0 Gaps": a {@code CommonForm} (concrete
 * {@code BasicForm} subtype) with {@code setObjectBelonging(ADOPTED)}, linked to a
 * {@code Form} via {@code AbstractForm.setMdForm(...)}.</p>
 */
public class ExtensionEventHandlerWiringTest {

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

    // --- Task 1: Wave-0 fixture feasibility (pure EMF, zero live BM transaction) ---

    @Test
    public void fixtureLinksAdoptedBasicFormToFormWithoutLiveTransaction() {
        Form form = createAdoptedFixture();

        assertEquals(ObjectBelonging.ADOPTED, form.getMdForm().getObjectBelonging());
    }

    @Test
    public void nativeFormFixtureReadsAsNative() {
        Form nativeFormWithNullMdForm = FormFactory.eINSTANCE.createForm();
        assertNull(nativeFormWithNullMdForm.getMdForm());

        Form nativeFormWithNativeMdForm = createNativeFixture();
        assertEquals(ObjectBelonging.NATIVE, nativeFormWithNativeMdForm.getMdForm().getObjectBelonging());
    }

    // --- Task 2 behaviors (filled in against the new production branch) ---

    @Test
    public void adoptedFormWiresEventHandlerExtensionWithDefaultCallType() throws Exception {
        Form form = createAdoptedFixture();

        applyFormModelOperations(form, List.of(
                opAddEventHandler("form", null, EVENT_ON_OPEN_EN, null, null))); //$NON-NLS-1$

        assertEquals(1, form.getHandlers().size());
        com._1c.g5.v8.dt.form.model.EventHandler handler = form.getHandlers().get(0);
        assertTrue(handler instanceof com._1c.g5.v8.dt.form.model.EventHandlerExtension);
        com._1c.g5.v8.dt.form.model.EventHandlerExtension extensionHandler =
                (com._1c.g5.v8.dt.form.model.EventHandlerExtension) handler;
        assertEquals(com._1c.g5.v8.dt.form.model.ExtendedMethodCallType.BEFORE, extensionHandler.getCallType());
    }

    @Test
    public void adoptedFormWithExplicitCallTypeAfter() throws Exception {
        Form form = createAdoptedFixture();

        applyFormModelOperations(form, List.of(
                opAddEventHandler("form", null, EVENT_ON_OPEN_EN, null, "After"))); //$NON-NLS-1$ //$NON-NLS-2$

        com._1c.g5.v8.dt.form.model.EventHandlerExtension extensionHandler =
                (com._1c.g5.v8.dt.form.model.EventHandlerExtension) form.getHandlers().get(0);
        assertEquals(com._1c.g5.v8.dt.form.model.ExtendedMethodCallType.AFTER, extensionHandler.getCallType());
    }

    @Test
    public void adoptedFormWithExplicitCallTypeOverride() throws Exception {
        Form form = createAdoptedFixture();

        applyFormModelOperations(form, List.of(
                opAddEventHandler("form", null, EVENT_ON_OPEN_EN, null, "Override"))); //$NON-NLS-1$ //$NON-NLS-2$

        com._1c.g5.v8.dt.form.model.EventHandlerExtension extensionHandler =
                (com._1c.g5.v8.dt.form.model.EventHandlerExtension) form.getHandlers().get(0);
        assertEquals(com._1c.g5.v8.dt.form.model.ExtendedMethodCallType.OVERRIDE, extensionHandler.getCallType());
    }

    @Test
    public void nativeFormStillWiresPlainEventHandler() throws Exception {
        Form form = createNativeFixture();

        applyFormModelOperations(form, List.of(
                opAddEventHandler("form", null, EVENT_ON_OPEN_EN, null, null))); //$NON-NLS-1$

        assertEquals(1, form.getHandlers().size());
        com._1c.g5.v8.dt.form.model.EventHandler handler = form.getHandlers().get(0);
        assertTrue(!(handler instanceof com._1c.g5.v8.dt.form.model.EventHandlerExtension));
    }

    @Test
    public void invalidCallTypeOnAdoptedFormThrowsAllowedValues() {
        Form form = createAdoptedFixture();

        try {
            applyFormModelOperations(form, List.of(
                    opAddEventHandler("form", null, EVENT_ON_OPEN_EN, null, "Instead"))); //$NON-NLS-1$ //$NON-NLS-2$
            org.junit.Assert.fail("expected MetadataOperationException"); //$NON-NLS-1$
        } catch (Exception e) {
            Throwable cause = e instanceof MetadataOperationException ? e : e.getCause();
            assertTrue(cause instanceof MetadataOperationException);
            MetadataOperationException metadataException = (MetadataOperationException) cause;
            assertEquals(MetadataOperationCode.METADATA_NOT_FOUND, metadataException.getCode());
            assertTrue(metadataException.getMessage().contains("Before")); //$NON-NLS-1$
            assertTrue(metadataException.getMessage().contains("After")); //$NON-NLS-1$
            assertTrue(metadataException.getMessage().contains("Override")); //$NON-NLS-1$
            assertTrue(metadataException.getMessage().contains("ChangeAndValidate")); //$NON-NLS-1$
        }
    }

    @Test
    public void callTypeEchoedInSummaryDefault() throws Exception {
        Form form = createAdoptedFixture();

        List<String> summaries = applyFormModelOperations(form, List.of(
                opAddEventHandler("form", null, EVENT_ON_OPEN_EN, null, null))); //$NON-NLS-1$

        assertEquals(1, summaries.size());
        assertTrue(summaries.get(0).contains("call_type=Before")); //$NON-NLS-1$
    }

    @Test
    public void callTypeEchoedInSummaryExplicit() throws Exception {
        Form form = createAdoptedFixture();

        List<String> summaries = applyFormModelOperations(form, List.of(
                opAddEventHandler("form", null, EVENT_ON_OPEN_EN, null, "After"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(1, summaries.size());
        assertTrue(summaries.get(0).contains("call_type=After")); //$NON-NLS-1$
    }

    @Test
    public void handlerNameNotPrefixed() throws Exception {
        Form form = createAdoptedFixture();

        applyFormModelOperations(form, List.of(
                opAddEventHandler("form", null, EVENT_ON_OPEN_EN, null, null))); //$NON-NLS-1$

        String name = form.getHandlers().get(0).getName();
        assertEquals("FormOnOpen", name); //$NON-NLS-1$
        assertTrue(!name.startsWith("ar_")); //$NON-NLS-1$
        assertTrue(!name.startsWith("ap_")); //$NON-NLS-1$
    }

    @Test
    public void baseHandlerExistsNameMatchedViaPureHelper() throws Exception {
        // Pure name-matching helper test (Pitfall 5): compare by Event.getName() String
        // equality, never by Event object reference — the base side resolves a SEPARATE
        // Event instance for the same event name.
        Method method = EdtMetadataService.class.getDeclaredMethod(
                "containsHandlerForEventName", //$NON-NLS-1$
                com._1c.g5.v8.dt.form.model.EventHandlerContainer.class, String.class);
        method.setAccessible(true);

        Form baseForm = FormFactory.eINSTANCE.createForm();
        com._1c.g5.v8.dt.form.model.EventHandler baseHandler = FormFactory.eINSTANCE.createEventHandler();
        Event separateOnOpenEventInstance = createEvent(EVENT_ON_OPEN_EN, EVENT_ON_OPEN_RU);
        baseHandler.setEvent(separateOnOpenEventInstance);
        baseHandler.setName("FormOnOpen"); //$NON-NLS-1$
        baseForm.getHandlers().add(baseHandler);

        boolean matchedByName = (Boolean) method.invoke(service, baseForm, EVENT_ON_OPEN_EN);
        boolean notMatchedForOtherEvent = (Boolean) method.invoke(service, baseForm, EVENT_ON_CHANGE_EN);

        assertTrue("expected name-matched base handler to be found", matchedByName); //$NON-NLS-1$
        assertTrue("expected no match for a different event name", !notMatchedForOtherEvent); //$NON-NLS-1$
        // Cross-check: separateOnOpenEventInstance is NOT the same object as onOpenEvent
        // (the adopted-side Event) — proving the match is by name, not reference.
        assertTrue(separateOnOpenEventInstance != onOpenEvent);
    }

    // --- Fixtures ---

    private static Form createAdoptedFixture() {
        CommonForm basicForm = MdClassFactory.eINSTANCE.createCommonForm();
        basicForm.setObjectBelonging(ObjectBelonging.ADOPTED);
        Form form = FormFactory.eINSTANCE.createForm();
        form.setMdForm(basicForm);
        basicForm.setForm(form);
        return form;
    }

    private static Form createNativeFixture() {
        CommonForm basicForm = MdClassFactory.eINSTANCE.createCommonForm();
        basicForm.setObjectBelonging(ObjectBelonging.NATIVE);
        Form form = FormFactory.eINSTANCE.createForm();
        form.setMdForm(basicForm);
        basicForm.setForm(form);
        return form;
    }

    private static Event createEvent(String nameEn, String nameRu) {
        Event event = McoreFactory.eINSTANCE.createEvent();
        event.setName(nameEn);
        event.setNameRu(nameRu);
        return event;
    }

    private static Map<String, Object> opAddEventHandler(
            String target, Integer itemId, String event, String handlerName, String callType) {
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
        if (callType != null) {
            operation.put("call_type", callType); //$NON-NLS-1$
        }
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
