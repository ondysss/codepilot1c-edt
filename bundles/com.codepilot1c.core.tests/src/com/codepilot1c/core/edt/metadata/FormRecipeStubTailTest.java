package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.mcore.Event;
import com._1c.g5.v8.dt.mcore.McoreFactory;

import com.codepilot1c.core.edt.forms.EventHandlerCatalog;
import com.codepilot1c.core.edt.forms.EventHandlerTargetResolver;
import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException;
import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException.BmState;
import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException.FailurePhase;
import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException.RollbackStatus;
import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException.SerializedModelState;

public class FormRecipeStubTailTest {

    private EdtMetadataService service;

    @Before
    public void setUp() {
        Event onOpen = McoreFactory.eINSTANCE.createEvent();
        onOpen.setName("OnOpen"); //$NON-NLS-1$
        onOpen.setNameRu("ПриОткрытии"); //$NON-NLS-1$
        EventHandlerCatalog catalog = target -> List.of(onOpen);
        service = new EdtMetadataService(
                new EdtMetadataGateway(), new EventHandlerTargetResolver(catalog));
    }

    @Test
    public void applyFormModelOperationsHasNoDiscardingOverload() throws Exception {
        try {
            EdtMetadataService.class.getDeclaredMethod(
                    "applyFormModelOperations", Form.class, List.class); //$NON-NLS-1$
            fail("discarding overload must not exist"); //$NON-NLS-1$
        } catch (NoSuchMethodException expected) {
            // Expected: every production and test caller must supply a PendingStub sink.
        }

        EdtMetadataService.class.getDeclaredMethod(
                "applyFormModelOperations", Form.class, List.class, List.class); //$NON-NLS-1$
    }

    @Test
    public void eventHandlerOperationRecordsPendingStub() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("op", "add_event_handler"); //$NON-NLS-1$ //$NON-NLS-2$
        operation.put("target", "form"); //$NON-NLS-1$ //$NON-NLS-2$
        operation.put("event", "ПриОткрытии"); //$NON-NLS-1$ //$NON-NLS-2$
        operation.put("handler_name", "FormOnOpen"); //$NON-NLS-1$ //$NON-NLS-2$
        List<Object> pendingStubs = new ArrayList<>();

        Method method = EdtMetadataService.class.getDeclaredMethod(
                "applyFormModelOperations", Form.class, List.class, List.class); //$NON-NLS-1$
        method.setAccessible(true);
        method.invoke(service, form, List.of(operation), pendingStubs);

        assertEquals(1, pendingStubs.size());
        Method handlerName = pendingStubs.get(0).getClass().getDeclaredMethod("handlerName"); //$NON-NLS-1$
        handlerName.setAccessible(true);
        assertEquals("FormOnOpen", handlerName.invoke(pendingStubs.get(0))); //$NON-NLS-1$
        Method createdHandlerSlot = pendingStubs.get(0).getClass().getDeclaredMethod("createdHandlerSlot"); //$NON-NLS-1$
        createdHandlerSlot.setAccessible(true);
        assertEquals(Boolean.TRUE, createdHandlerSlot.invoke(pendingStubs.get(0)));
    }

    @Test
    public void existingHandlerUpsertCarriesRestorableSnapshot() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("op", "add_event_handler"); //$NON-NLS-1$ //$NON-NLS-2$
        first.put("target", "form"); //$NON-NLS-1$ //$NON-NLS-2$
        first.put("event", "ПриОткрытии"); //$NON-NLS-1$ //$NON-NLS-2$
        first.put("handler_name", "OriginalOnOpen"); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, Object> upsert = new LinkedHashMap<>(first);
        upsert.put("handler_name", "RenamedOnOpen"); //$NON-NLS-1$ //$NON-NLS-2$
        List<Object> pendingStubs = new ArrayList<>();

        Method method = EdtMetadataService.class.getDeclaredMethod(
                "applyFormModelOperations", Form.class, List.class, List.class); //$NON-NLS-1$
        method.setAccessible(true);
        method.invoke(service, form, List.of(first, upsert), pendingStubs);

        assertEquals(2, pendingStubs.size());
        Method createdHandlerSlot = pendingStubs.get(1).getClass().getDeclaredMethod("createdHandlerSlot"); //$NON-NLS-1$
        createdHandlerSlot.setAccessible(true);
        assertEquals(Boolean.FALSE, createdHandlerSlot.invoke(pendingStubs.get(1)));
        Method previousState = pendingStubs.get(1).getClass().getDeclaredMethod("previousState"); //$NON-NLS-1$
        previousState.setAccessible(true);
        Object snapshot = previousState.invoke(pendingStubs.get(1));
        Method existed = snapshot.getClass().getDeclaredMethod("existed"); //$NON-NLS-1$
        Method handlerName = snapshot.getClass().getDeclaredMethod("handlerName"); //$NON-NLS-1$
        existed.setAccessible(true);
        handlerName.setAccessible(true);
        assertEquals(Boolean.TRUE, existed.invoke(snapshot));
        assertEquals("OriginalOnOpen", handlerName.invoke(snapshot)); //$NON-NLS-1$
    }

    @Test
    public void recipeSourceInvokesStubTailAfterExportVerification() throws Exception {
        Path source = locateServiceSource();
        String text = Files.readString(source);
        int methodStart = text.indexOf("public FormRecipeResult applyFormRecipe"); //$NON-NLS-1$
        int methodEnd = text.indexOf("private void applyFormRootPropertiesIfNeeded", methodStart); //$NON-NLS-1$
        assertTrue("method end marker not found", methodEnd > methodStart); //$NON-NLS-1$
        String method = text.substring(methodStart, methodEnd);

        int verify = method.indexOf("verifyObjectPersisted(project, formFqn, opId)"); //$NON-NLS-1$
        int write = method.indexOf("writeHandlerStubsDetailed("); //$NON-NLS-1$
        int refresh = method.indexOf("refreshProjectSafely(project)"); //$NON-NLS-1$

        assertTrue("recipe must verify export before writing stubs", verify >= 0 && write > verify); //$NON-NLS-1$
        assertTrue("recipe must write stubs before refresh", refresh > write); //$NON-NLS-1$
        assertTrue(method.contains(
                "applyFormModelOperations(formModel, request.layoutOperations(), pendingStubs,")); //$NON-NLS-1$
        assertTrue(method.contains("catch (MetadataOperationException e)")); //$NON-NLS-1$
        assertTrue(method.contains("formRecipePartialFailure(")); //$NON-NLS-1$
        assertFalse(method.contains("pendingStubs.size() - 1")); //$NON-NLS-1$
    }

    @Test
    public void writeFailureSurfacesActualRollbackAndPartialOutcome() {
        MetadataOperationException cause = new MetadataOperationException(
                MetadataOperationCode.EDT_TRANSACTION_FAILED,
                "Stub write failed for handler 'ItemOnChange'; rollback_status=REMOVED_AND_EXPORTED", //$NON-NLS-1$
                true);

        FormRecipePartialFailureException failure = EdtMetadataService.formRecipePartialFailure(
                cause,
                "Catalog.Products.Form.ItemForm", //$NON-NLS-1$
                false,
                2,
                1,
                0,
                3,
                List.of("FormOnOpen", "ItemOnChange", "LaterHandler"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                List.of("FormOnOpen"), //$NON-NLS-1$
                List.of(),
                FailurePhase.WRITE_STUB,
                "ItemOnChange", //$NON-NLS-1$
                RollbackStatus.REMOVED_AND_EXPORTED,
                BmState.HANDLER_REMOVED,
                SerializedModelState.ROLLBACK_EXPORT_VERIFIED);

        assertEquals(MetadataOperationCode.EDT_TRANSACTION_FAILED, failure.getCode());
        assertTrue(failure.isRecoverable());
        assertSame(cause, failure.getCause());
        assertEquals(RollbackStatus.REMOVED_AND_EXPORTED, failure.rollbackStatus());
        assertEquals(BmState.HANDLER_REMOVED, failure.bmState());
        assertEquals(SerializedModelState.ROLLBACK_EXPORT_VERIFIED, failure.serializedModelState());
        assertEquals(List.of("LaterHandler"), failure.handlerSlotsWithoutWrittenStub()); //$NON-NLS-1$
        assertTrue(failure.getMessage().contains(
                "handler_slots_without_written_stub=[LaterHandler]")); //$NON-NLS-1$
        assertTrue(failure.getMessage().contains("layout_operations_initially_committed=3")); //$NON-NLS-1$
        assertTrue(failure.getMessage().contains("rollback_status=REMOVED_AND_EXPORTED")); //$NON-NLS-1$
    }

    @Test
    public void confirmedAbsentSlotIsNotReportedAsMissingAStub() {
        MetadataOperationException cause = new MetadataOperationException(
                MetadataOperationCode.EDT_TRANSACTION_FAILED,
                "Handler slot was already absent", //$NON-NLS-1$
                true);

        FormRecipePartialFailureException failure = EdtMetadataService.formRecipePartialFailure(
                cause,
                "Catalog.Products.Form.ItemForm", //$NON-NLS-1$
                false,
                0,
                0,
                0,
                1,
                List.of("ItemOnChange"), //$NON-NLS-1$
                List.of(),
                List.of(),
                FailurePhase.WRITE_STUB,
                "ItemOnChange", //$NON-NLS-1$
                RollbackStatus.SLOT_NOT_FOUND,
                BmState.HANDLER_ABSENCE_CONFIRMED,
                SerializedModelState.INITIAL_EXPORT_VERIFIED);

        assertEquals(List.of(), failure.handlerSlotsWithoutWrittenStub());
        assertTrue(failure.getMessage().contains("handler_slots_without_written_stub=[]")); //$NON-NLS-1$
    }

    @Test
    public void externalNoConfigurationFailureIsExplicitAndDoesNotClaimRollback() {
        MetadataOperationException cause = new MetadataOperationException(
                MetadataOperationCode.METADATA_NOT_FOUND,
                "External form could not be re-resolved", //$NON-NLS-1$
                false);

        FormRecipePartialFailureException failure = EdtMetadataService.formRecipePartialFailure(
                cause,
                "ExternalReport.Report.Form.Main", //$NON-NLS-1$
                true,
                0,
                0,
                0,
                1,
                List.of("FormOnOpen"), //$NON-NLS-1$
                List.of(),
                List.of(),
                FailurePhase.RESOLVE_EVENT,
                "FormOnOpen", //$NON-NLS-1$
                RollbackStatus.NOT_ATTEMPTED,
                BmState.INITIAL_COMMIT_REMAINS,
                SerializedModelState.INITIAL_EXPORT_VERIFIED);

        assertTrue(failure.externalProject());
        assertEquals(RollbackStatus.NOT_ATTEMPTED, failure.rollbackStatus());
        assertEquals(BmState.INITIAL_COMMIT_REMAINS, failure.bmState());
        assertEquals(List.of("FormOnOpen"), failure.handlerSlotsWithoutWrittenStub()); //$NON-NLS-1$
        assertTrue(failure.getMessage().contains("external_project=true")); //$NON-NLS-1$
        assertTrue(failure.getMessage().contains("bm_export_verified_before_stub_phase=true")); //$NON-NLS-1$
    }

    @Test
    public void freshEventResolutionUsesExternalAwarePathWithoutConfigurationGuard() throws Exception {
        String text = Files.readString(locateServiceSource());
        int methodStart = text.indexOf("private FreshEventContext resolveFreshEventContext"); //$NON-NLS-1$
        int methodEnd = text.indexOf("private RollbackMutationResult rollbackHandlerSlot", methodStart); //$NON-NLS-1$
        assertTrue("resolveFreshEventContext end marker not found", methodEnd > methodStart); //$NON-NLS-1$
        String method = text.substring(methodStart, methodEnd);

        assertTrue(method.contains("resolveObjectForTransaction(")); //$NON-NLS-1$
        assertFalse(method.contains("Cannot access configuration in BM read transaction")); //$NON-NLS-1$
    }

    private static Path locateServiceSource() {
        Path moduleRelative = Path.of("..", "com.codepilot1c.core", "src", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "com", "codepilot1c", "core", "edt", "metadata", "EdtMetadataService.java"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        if (Files.isRegularFile(moduleRelative)) {
            return moduleRelative;
        }
        Path reactorRelative = Path.of("bundles", "com.codepilot1c.core", "src", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "com", "codepilot1c", "core", "edt", "metadata", "EdtMetadataService.java"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        if (Files.isRegularFile(reactorRelative)) {
            return reactorRelative;
        }
        throw new AssertionError("Cannot locate EdtMetadataService.java from " //$NON-NLS-1$
                + Path.of("").toAbsolutePath()); //$NON-NLS-1$
    }
}
