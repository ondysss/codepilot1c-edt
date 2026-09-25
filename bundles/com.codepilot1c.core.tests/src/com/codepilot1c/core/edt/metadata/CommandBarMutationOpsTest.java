package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.AutoCommandBar;
import com._1c.g5.v8.dt.form.model.Button;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormCommand;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.FormStandardCommand;
import com._1c.g5.v8.dt.form.model.Table;
import com._1c.g5.v8.dt.platform.model.PlatformPicture;

import com.codepilot1c.core.edt.forms.EventHandlerCatalog;
import com.codepilot1c.core.edt.forms.EventHandlerTargetResolver;

/**
 * Covers the {@code set_auto_command_bar}/{@code set_excluded_commands} wiring in
 * {@link EdtMetadataService#applyFormModelOperations} (built-in list/table command bar
 * support — CommandBarHolder.autoCommandBar / FormStandardCommandSource.excludedCommands).
 *
 * <p>Runs entirely against real EDT EMF model classes ({@code FormFactory.eINSTANCE}), no
 * live EDT/BM/OSGi dependency, matching the {@code EventHandlerWiringTest} seam.</p>
 */
public class CommandBarMutationOpsTest {

    private EdtMetadataService service;

    @Before
    public void setUp() {
        EventHandlerCatalog emptyCatalog = item -> List.of();
        service = new EdtMetadataService(new EdtMetadataGateway(), new EventHandlerTargetResolver(emptyCatalog));
    }

    // --- set_auto_command_bar -------------------------------------------------

    @Test
    public void setAutoCommandBarOnFormRootUpdatesAutoFill() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        AutoCommandBar autoBar = FormFactory.eINSTANCE.createAutoCommandBar();
        autoBar.setAutoFill(false);
        form.setAutoCommandBar(autoBar);

        List<String> summaries = applyFormModelOperations(form, List.of(
                opSetAutoCommandBar("form", null, null, Boolean.TRUE))); //$NON-NLS-1$

        assertTrue(autoBar.isAutoFill());
        assertEquals(1, summaries.size());
        assertTrue(summaries.get(0).contains("set_auto_command_bar")); //$NON-NLS-1$
    }

    @Test
    public void setAutoCommandBarByItemIdTargetsTableCommandBar() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        Table table = createListTable(form, 1, "List"); //$NON-NLS-1$
        AutoCommandBar autoBar = FormFactory.eINSTANCE.createAutoCommandBar();
        autoBar.setAutoFill(true);
        table.setAutoCommandBar(autoBar);

        applyFormModelOperations(form, List.of(
                opSetAutoCommandBar(null, Integer.valueOf(1), null, Boolean.FALSE)));

        assertFalse(autoBar.isAutoFill());
    }

    @Test
    public void setAutoCommandBarByItemNameResolvesTarget() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        Table table = createListTable(form, 1, "List"); //$NON-NLS-1$
        AutoCommandBar autoBar = FormFactory.eINSTANCE.createAutoCommandBar();
        autoBar.setAutoFill(true);
        table.setAutoCommandBar(autoBar);

        applyFormModelOperations(form, List.of(
                opSetAutoCommandBar(null, null, "List", Boolean.FALSE))); //$NON-NLS-1$

        assertFalse(autoBar.isAutoFill());
    }

    @Test
    public void setAutoCommandBarMissingAutoFillThrowsInvalidMetadataChange() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        form.setAutoCommandBar(FormFactory.eINSTANCE.createAutoCommandBar());

        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("op", "set_auto_command_bar"); //$NON-NLS-1$ //$NON-NLS-2$
        operation.put("target", "form"); //$NON-NLS-1$ //$NON-NLS-2$

        try {
            applyFormModelOperations(form, List.of(operation));
            fail("expected MetadataOperationException"); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.INVALID_METADATA_CHANGE, e.getCode());
            assertTrue(e.getMessage().contains("auto_fill")); //$NON-NLS-1$
        }
    }

    @Test
    public void setAutoCommandBarOnNonCommandBarHolderThrowsInvalidMetadataChange() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        FormField field = FormFactory.eINSTANCE.createFormField();
        field.setId(1);
        field.setName("MyField"); //$NON-NLS-1$
        form.getItems().add(field);

        try {
            applyFormModelOperations(form, List.of(
                    opSetAutoCommandBar(null, Integer.valueOf(1), null, Boolean.TRUE)));
            fail("expected MetadataOperationException"); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.INVALID_METADATA_CHANGE, e.getCode());
            assertTrue(e.getMessage().contains("command-bar holder")); //$NON-NLS-1$
        }
    }

    @Test
    public void setAutoCommandBarWithNoAutoCommandBarPresentThrowsInvalidMetadataChange() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        Table table = createListTable(form, 1, "List"); //$NON-NLS-1$
        // table.autoCommandBar left null — nothing discovered to configure

        try {
            applyFormModelOperations(form, List.of(
                    opSetAutoCommandBar(null, Integer.valueOf(1), null, Boolean.TRUE)));
            fail("expected MetadataOperationException"); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.INVALID_METADATA_CHANGE, e.getCode());
            assertTrue(e.getMessage().contains("autoCommandBar")); //$NON-NLS-1$
        }
    }

    // --- set_excluded_commands -------------------------------------------------

    @Test
    public void setExcludedCommandsReplaceModeIsDefault() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        Table table = createListTable(form, 1, "List"); //$NON-NLS-1$
        FormStandardCommand add = standardCommand("Add"); //$NON-NLS-1$
        FormStandardCommand delete = standardCommand("Delete"); //$NON-NLS-1$
        FormStandardCommand copy = standardCommand("Copy"); //$NON-NLS-1$
        table.getCommands().add(add);
        table.getCommands().add(delete);
        table.getCommands().add(copy);

        List<String> summaries = applyFormModelOperations(form, List.of(
                opSetExcludedCommands(Integer.valueOf(1), null, List.of("Add", "Copy"), null))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(List.of(add, copy), table.getExcludedCommands());
        assertEquals(1, summaries.size());
        assertTrue(summaries.get(0).contains("set_excluded_commands")); //$NON-NLS-1$
        assertTrue(summaries.get(0).contains("mode=replace")); //$NON-NLS-1$
    }

    @Test
    public void setExcludedCommandsAddModeAppendsWithoutDuplicates() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        Table table = createListTable(form, 1, "List"); //$NON-NLS-1$
        FormStandardCommand add = standardCommand("Add"); //$NON-NLS-1$
        FormStandardCommand delete = standardCommand("Delete"); //$NON-NLS-1$
        table.getCommands().add(add);
        table.getCommands().add(delete);
        table.getExcludedCommands().add(delete);

        applyFormModelOperations(form, List.of(
                opSetExcludedCommands(Integer.valueOf(1), null, List.of("Add"), "add"))); //$NON-NLS-1$ //$NON-NLS-2$
        applyFormModelOperations(form, List.of(
                opSetExcludedCommands(Integer.valueOf(1), null, List.of("Add"), "add"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(List.of(delete, add), table.getExcludedCommands());
    }

    @Test
    public void setExcludedCommandsRemoveModeRemovesRequestedCommands() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        Table table = createListTable(form, 1, "List"); //$NON-NLS-1$
        FormStandardCommand add = standardCommand("Add"); //$NON-NLS-1$
        FormStandardCommand delete = standardCommand("Delete"); //$NON-NLS-1$
        table.getCommands().add(add);
        table.getCommands().add(delete);
        table.getExcludedCommands().add(add);
        table.getExcludedCommands().add(delete);

        applyFormModelOperations(form, List.of(
                opSetExcludedCommands(Integer.valueOf(1), null, List.of("Add"), "remove"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(List.of(delete), table.getExcludedCommands());
    }

    @Test
    public void setExcludedCommandsUnknownCommandThrowsMetadataNotFoundWithAllowedList() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        Table table = createListTable(form, 1, "List"); //$NON-NLS-1$
        table.getCommands().add(standardCommand("Add")); //$NON-NLS-1$

        try {
            applyFormModelOperations(form, List.of(
                    opSetExcludedCommands(Integer.valueOf(1), null, List.of("Nonexistent"), null))); //$NON-NLS-1$
            fail("expected MetadataOperationException"); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.METADATA_NOT_FOUND, e.getCode());
            assertTrue(e.getMessage().contains("Nonexistent")); //$NON-NLS-1$
            assertTrue(e.getMessage().contains("Add")); //$NON-NLS-1$
        }
    }

    @Test
    public void setExcludedCommandsEmptyListClearsForReplaceMode() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        Table table = createListTable(form, 1, "List"); //$NON-NLS-1$
        FormStandardCommand add = standardCommand("Add"); //$NON-NLS-1$
        table.getCommands().add(add);
        table.getExcludedCommands().add(add);

        List<String> summaries = applyFormModelOperations(form, List.of(
                opSetExcludedCommands(Integer.valueOf(1), null, List.of(), null)));

        assertTrue(table.getExcludedCommands().isEmpty());
        assertTrue(summaries.get(0).contains("mode=replace")); //$NON-NLS-1$
        assertTrue(summaries.get(0).contains("count=0")); //$NON-NLS-1$
    }

    @Test
    public void setExcludedCommandsMissingListThrowsWithoutClearingExistingExclusions() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        Table table = createListTable(form, 1, "List"); //$NON-NLS-1$
        FormStandardCommand add = standardCommand("Add"); //$NON-NLS-1$
        table.getCommands().add(add);
        table.getExcludedCommands().add(add);
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("op", "set_excluded_commands"); //$NON-NLS-1$ //$NON-NLS-2$
        operation.put("item_id", Integer.valueOf(1)); //$NON-NLS-1$

        try {
            applyFormModelOperations(form, List.of(operation));
            fail("expected MetadataOperationException"); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.INVALID_METADATA_CHANGE, e.getCode());
            assertTrue(e.getMessage().contains("excluded_commands")); //$NON-NLS-1$
            assertEquals(List.of(add), table.getExcludedCommands());
        }
    }

    @Test
    public void setExcludedCommandsNonArrayThrowsWithoutClearingExistingExclusions() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        Table table = createListTable(form, 1, "List"); //$NON-NLS-1$
        FormStandardCommand add = standardCommand("Add"); //$NON-NLS-1$
        table.getCommands().add(add);
        table.getExcludedCommands().add(add);
        Map<String, Object> operation = opSetExcludedCommands(Integer.valueOf(1), null, null, null);
        operation.put("excluded_commands", "Add"); //$NON-NLS-1$ //$NON-NLS-2$

        try {
            applyFormModelOperations(form, List.of(operation));
            fail("expected MetadataOperationException"); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.INVALID_METADATA_CHANGE, e.getCode());
            assertTrue(e.getMessage().contains("excluded_commands")); //$NON-NLS-1$
            assertEquals(List.of(add), table.getExcludedCommands());
        }
    }

    @Test
    public void setExcludedCommandsOnNonCommandBarHolderThrowsInvalidMetadataChange() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        FormField field = FormFactory.eINSTANCE.createFormField();
        field.setId(1);
        field.setName("MyField"); //$NON-NLS-1$
        form.getItems().add(field);

        try {
            applyFormModelOperations(form, List.of(
                    opSetExcludedCommands(Integer.valueOf(1), null, List.of("Add"), null))); //$NON-NLS-1$
            fail("expected MetadataOperationException"); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.INVALID_METADATA_CHANGE, e.getCode());
            assertTrue(e.getMessage().contains("command-bar holder")); //$NON-NLS-1$
        }
    }

    // --- add_button into built-in command bar -----------------------------------

    @Test
    public void addButtonWithTableParentTargetsBuiltInAutoCommandBar() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        Table table = createListTable(form, 1, "List"); //$NON-NLS-1$
        AutoCommandBar autoBar = FormFactory.eINSTANCE.createAutoCommandBar();
        table.setAutoCommandBar(autoBar);
        FormCommand command = FormFactory.eINSTANCE.createFormCommand();
        command.setName("Run"); //$NON-NLS-1$
        form.getFormCommands().add(command);

        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("op", "add_button"); //$NON-NLS-1$ //$NON-NLS-2$
        operation.put("parent_item_id", Integer.valueOf(1)); //$NON-NLS-1$
        operation.put("name", "RunButton"); //$NON-NLS-1$ //$NON-NLS-2$
        operation.put("command_name", "Run"); //$NON-NLS-1$ //$NON-NLS-2$

        applyFormModelOperations(form, List.of(operation));

        assertTrue("table children must stay untouched; button belongs to autoCommandBar", //$NON-NLS-1$
                table.getItems().isEmpty());
        assertEquals(1, autoBar.getItems().size());
        assertTrue(autoBar.getItems().get(0) instanceof Button);
        Button button = (Button) autoBar.getItems().get(0);
        assertEquals("RunButton", button.getName()); //$NON-NLS-1$
        assertEquals(command, button.getCommandName());
    }

    @Test
    public void addButtonWithoutParentTargetsFormBuiltInAutoCommandBar() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        AutoCommandBar autoBar = FormFactory.eINSTANCE.createAutoCommandBar();
        form.setAutoCommandBar(autoBar);
        FormCommand command = FormFactory.eINSTANCE.createFormCommand();
        command.setName("Run"); //$NON-NLS-1$
        form.getFormCommands().add(command);

        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("op", "add_button"); //$NON-NLS-1$ //$NON-NLS-2$
        operation.put("name", "FormRunButton"); //$NON-NLS-1$ //$NON-NLS-2$
        operation.put("command_name", "Run"); //$NON-NLS-1$ //$NON-NLS-2$

        applyFormModelOperations(form, List.of(operation));

        assertTrue("form root items must stay untouched; button belongs to form autoCommandBar", //$NON-NLS-1$
                form.getItems().isEmpty());
        assertEquals(1, autoBar.getItems().size());
        assertTrue(autoBar.getItems().get(0) instanceof Button);
        Button button = (Button) autoBar.getItems().get(0);
        assertEquals("FormRunButton", button.getName()); //$NON-NLS-1$
        assertEquals(command, button.getCommandName());
    }

    @Test
    public void addCommandStoresStdPictureName() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        Map<String, Object> operation = commandOperation("Run", "RunAction"); //$NON-NLS-1$ //$NON-NLS-2$
        operation.put("picture", "StdPicture.ExecuteTask"); //$NON-NLS-1$ //$NON-NLS-2$

        applyFormModelOperations(form, List.of(operation));

        assertEquals(1, form.getFormCommands().size());
        FormCommand command = form.getFormCommands().get(0);
        assertTrue(command.getPicture() instanceof PlatformPicture);
        PlatformPicture picture = (PlatformPicture) command.getPicture();
        assertEquals("ExecuteTask", picture.getName()); //$NON-NLS-1$
        assertEquals("StdPicture.ExecuteTask", picture.getUrl()); //$NON-NLS-1$
    }

    // --- regression: generic reference rejection is unaffected ------------------

    @Test
    public void setFormPropsStillRejectsAutoCommandBarReferenceUpdate() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        form.setAutoCommandBar(FormFactory.eINSTANCE.createAutoCommandBar());

        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("op", "set_form_props"); //$NON-NLS-1$ //$NON-NLS-2$
        operation.put("set", Map.of("autoCommandBar", Map.of("autoFill", Boolean.TRUE))); //$NON-NLS-1$ //$NON-NLS-2$

        try {
            applyFormModelOperations(form, List.of(operation));
            fail("expected MetadataOperationException"); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.INVALID_METADATA_CHANGE, e.getCode());
            assertTrue(e.getMessage().contains("Reference property updates are not supported directly")); //$NON-NLS-1$
            assertTrue(e.getMessage().contains("autoCommandBar")); //$NON-NLS-1$
        }
    }

    @Test
    public void setItemStillRejectsExcludedCommandsReferenceUpdate() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        Table table = createListTable(form, 1, "List"); //$NON-NLS-1$

        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("op", "set_item"); //$NON-NLS-1$ //$NON-NLS-2$
        operation.put("item_id", Integer.valueOf(1)); //$NON-NLS-1$
        operation.put("set", Map.of("excludedCommands", List.of("Add"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        try {
            applyFormModelOperations(form, List.of(operation));
            fail("expected MetadataOperationException"); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.INVALID_METADATA_CHANGE, e.getCode());
            assertTrue(e.getMessage().contains("Reference property updates are not supported directly")); //$NON-NLS-1$
            assertTrue(e.getMessage().contains("excludedCommands")); //$NON-NLS-1$
        }
    }

    // --- fixtures ---------------------------------------------------------------

    private static Table createListTable(Form form, int id, String name) {
        Table table = FormFactory.eINSTANCE.createTable();
        table.setId(id);
        table.setName(name);
        form.getItems().add(table);
        return table;
    }

    private static FormStandardCommand standardCommand(String name) {
        FormStandardCommand command = FormFactory.eINSTANCE.createFormStandardCommand();
        command.setName(name);
        return command;
    }

    private static Map<String, Object> commandOperation(String name, String action) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("op", "add_command"); //$NON-NLS-1$ //$NON-NLS-2$
        operation.put("name", name); //$NON-NLS-1$
        if (action != null) {
            operation.put("action", action); //$NON-NLS-1$
        }
        return operation;
    }

    private static Map<String, Object> opSetAutoCommandBar(
            String target, Integer itemId, String itemName, Boolean autoFill) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("op", "set_auto_command_bar"); //$NON-NLS-1$ //$NON-NLS-2$
        if (target != null) {
            operation.put("target", target); //$NON-NLS-1$
        }
        if (itemId != null) {
            operation.put("item_id", itemId); //$NON-NLS-1$
        }
        if (itemName != null) {
            operation.put("item_name", itemName); //$NON-NLS-1$
        }
        if (autoFill != null) {
            operation.put("auto_fill", autoFill); //$NON-NLS-1$
        }
        return operation;
    }

    private static Map<String, Object> opSetExcludedCommands(
            Integer itemId, String itemName, List<String> excludedCommands, String mode) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("op", "set_excluded_commands"); //$NON-NLS-1$ //$NON-NLS-2$
        if (itemId != null) {
            operation.put("item_id", itemId); //$NON-NLS-1$
        }
        if (itemName != null) {
            operation.put("item_name", itemName); //$NON-NLS-1$
        }
        operation.put("excluded_commands", excludedCommands); //$NON-NLS-1$
        if (mode != null) {
            operation.put("mode", mode); //$NON-NLS-1$
        }
        return operation;
    }

    @SuppressWarnings("unchecked")
    private List<String> applyFormModelOperations(Form formModel, List<Map<String, Object>> operations)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        Method method = EdtMetadataService.class.getDeclaredMethod(
                "applyFormModelOperations", Form.class, List.class, List.class); //$NON-NLS-1$
        method.setAccessible(true);
        try {
            return (List<String>) method.invoke(service, formModel, operations, new ArrayList<>());
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
    }
}
