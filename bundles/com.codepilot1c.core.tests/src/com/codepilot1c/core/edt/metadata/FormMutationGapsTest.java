package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.ecore.EObject;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.AutoCommandBar;
import com._1c.g5.v8.dt.form.model.Button;
import com._1c.g5.v8.dt.form.model.ContextMenu;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormGroup;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.form.model.FormItemContainer;
import com._1c.g5.v8.dt.form.model.FormStandardCommand;
import com._1c.g5.v8.dt.form.model.FormStandardCommandSource;
import com._1c.g5.v8.dt.form.model.ManagedFormButtonType;
import com._1c.g5.v8.dt.form.model.ManagedFormGroupType;
import com._1c.g5.v8.dt.form.model.PageGroupExtInfo;
import com._1c.g5.v8.dt.form.model.Table;
import com._1c.g5.v8.dt.form.model.UsualGroupExtInfo;

import com.codepilot1c.core.edt.forms.EventHandlerTargetResolver;
import com.codepilot1c.core.edt.forms.InspectFormLayoutRequest;
import com.codepilot1c.core.edt.forms.InspectFormLayoutResult;

/**
 * Regression net for three gaps of {@code mutate_form_model} found on 2026-09-14 while building
 * managed forms through the tool.
 *
 * <ol>
 * <li>{@code excludedCommands} on the form or on a table was rejected as a reference property, and
 * the whole batch with it.</li>
 * <li>{@code titleDataPath} of a page or usual group was rejected the same way.</li>
 * <li>The auto command bar ({@code ФормаКоманднаяПанель}, {@code <Table>КоманднаяПанель}) lives in
 * {@code CommandBarHolder.autoCommandBar}, outside {@code getItems()}: it could not be a move target,
 * buttons inside it could not be found, {@code add_button} without a parent dropped the button at the
 * form root, and {@code inspect_form_layout} did not show the bar at all.</li>
 * </ol>
 *
 * <p>The real {@code applyFormModelOperations}/{@code collectFormItemNodes} are invoked by
 * reflection, the same way {@link EventHandlerWiringTest} and {@link EventHandlerInspectTest} do;
 * without OSGi the EDT item services resolve to {@code null}, so the manual model path is the one
 * under test here. The EDT movement service is exercised only in a running EDT.</p>
 */
public class FormMutationGapsTest {

    private static final String FORM_COMMAND_BAR = "ФормаКоманднаяПанель"; //$NON-NLS-1$

    private EdtMetadataService service;

    @Before
    public void setUp() {
        service = new EdtMetadataService(new EdtMetadataGateway(), new EventHandlerTargetResolver(item -> List.of()));
    }

    // ─── 1. excludedCommands ────────────────────────────────────────────────

    @Test
    public void setFormPropsExcludesFormStandardCommandsByEnglishOrRussianName() {
        Form form = formWithAutoCommandBar();
        standardCommands(form, "Copy/Скопировать", "Delete/Удалить", "Write/Записать", "Post/Провести"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        apply(form, op("set_form_props", "set", Map.of("excludedCommands", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                List.of("Write", "Удалить", "copy")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(List.of("Copy", "Delete", "Write"), names(form.getExcludedCommands())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // The exclusion must reference the source's own command object: EDT serializes the
        // reference by name and compares it by URI, a detached copy would not resolve.
        for (FormStandardCommand excluded : form.getExcludedCommands()) {
            assertTrue(form.getCommands().contains(excluded));
        }
    }

    @Test
    public void excludedCommandsValueReplacesTheWholeListAndEmptyListClearsIt() {
        Form form = formWithAutoCommandBar();
        standardCommands(form, "Copy/Скопировать", "Delete/Удалить"); //$NON-NLS-1$ //$NON-NLS-2$

        apply(form, op("set_form_props", "set", Map.of("excludedCommands", List.of("Copy")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        apply(form, op("set_form_props", "set", Map.of("excludedCommands", List.of("Delete")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals(List.of("Delete"), names(form.getExcludedCommands())); //$NON-NLS-1$

        apply(form, op("set_form_props", "set", Map.of("excludedCommands", List.of()))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(form.getExcludedCommands().isEmpty());
    }

    @Test
    public void unknownStandardCommandIsRejectedAndListsWhatTheSourceHas() {
        Form form = formWithAutoCommandBar();
        standardCommands(form, "Copy/Скопировать", "Delete/Удалить"); //$NON-NLS-1$ //$NON-NLS-2$

        MetadataOperationException failure = expectFailure(() -> apply(form,
                op("set_form_props", "set", Map.of("excludedCommands", List.of("Copy", "PostAndClose"))))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        assertEquals(MetadataOperationCode.INVALID_PROPERTY_VALUE, failure.getCode());
        assertTrue(failure.getMessage(), failure.getMessage().contains("PostAndClose")); //$NON-NLS-1$
        assertTrue(failure.getMessage(), failure.getMessage().contains("Delete")); //$NON-NLS-1$
        assertTrue("a rejected value must not half-apply", form.getExcludedCommands().isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void excludedCommandsFailLoudlyWhenTheSourceHasNoComputedStandardCommands() {
        Form form = formWithAutoCommandBar();

        MetadataOperationException failure = expectFailure(() -> apply(form,
                op("set_form_props", "set", Map.of("excludedCommands", List.of("Copy"))))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertEquals(MetadataOperationCode.INVALID_PROPERTY_VALUE, failure.getCode());
        assertTrue(failure.getMessage(), failure.getMessage().contains("Copy")); //$NON-NLS-1$
    }

    @Test
    public void setItemExcludesCommandsOfTheTableItselfNotOfTheForm() {
        Form form = formWithAutoCommandBar();
        standardCommands(form, "Copy/Скопировать"); //$NON-NLS-1$
        Table table = table(10, "Заказы"); //$NON-NLS-1$
        form.getItems().add(table);
        standardCommands(table, "Add/Добавить", "Delete/Удалить", "SetDeletionMark/УстановитьПометкуУдаления"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        apply(form, op("set_item", "item_id", Integer.valueOf(10), "set", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                Map.of("excludedCommands", List.of("Delete", "SetDeletionMark")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(List.of("Delete", "SetDeletionMark"), names(table.getExcludedCommands())); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(form.getExcludedCommands().isEmpty());
        expectFailure(() -> apply(form, op("set_item", "item_id", Integer.valueOf(10), "set", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                Map.of("excludedCommands", List.of("Copy"))))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ─── 2. titleDataPath ───────────────────────────────────────────────────

    @Test
    public void setItemWritesTitleDataPathOfAPage() {
        Form form = formWithAutoCommandBar();
        FormGroup page = group(3, "СтраницаЗаказы", ManagedFormGroupType.PAGE); //$NON-NLS-1$
        form.getItems().add(page);

        apply(form, op("set_item", "item_id", Integer.valueOf(3), "set", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                Map.of("titleDataPath", "Объект.Заказы.RowsCount"))); //$NON-NLS-1$ //$NON-NLS-2$

        PageGroupExtInfo extInfo = (PageGroupExtInfo) page.getExtInfo();
        assertNotNull(extInfo.getTitleDataPath());
        assertEquals("Объект.Заказы.RowsCount", String.join(".", extInfo.getTitleDataPath().getSegments())); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void setItemWritesAndClearsTitleDataPathOfAUsualGroup() {
        Form form = formWithAutoCommandBar();
        FormGroup usual = group(4, "ГруппаШапка", ManagedFormGroupType.USUAL_GROUP); //$NON-NLS-1$
        form.getItems().add(usual);

        apply(form, op("set_item", "item_name", "ГруппаШапка", "set", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                Map.of("titleDataPath", "Объект.Номер"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Объект.Номер", //$NON-NLS-1$
                String.join(".", ((UsualGroupExtInfo) usual.getExtInfo()).getTitleDataPath().getSegments())); //$NON-NLS-1$

        Map<String, Object> clear = new LinkedHashMap<>();
        clear.put("titleDataPath", ""); //$NON-NLS-1$ //$NON-NLS-2$
        apply(form, op("set_item", "item_name", "ГруппаШапка", "set", clear)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertNull(((UsualGroupExtInfo) usual.getExtInfo()).getTitleDataPath());
    }

    // ─── 3. auto command bar ────────────────────────────────────────────────

    @Test
    public void moveItemAcceptsTheFormAutoCommandBarByNameAndMakesTheButtonACommandBarButton() {
        Form form = formWithAutoCommandBar();
        Button button = button(7, "КнопкаОбновить", ManagedFormButtonType.USUAL_BUTTON); //$NON-NLS-1$
        form.getItems().add(button);

        apply(form, op("move_item", "item_id", Integer.valueOf(7), "parent_item_name", FORM_COMMAND_BAR)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertSame(form.getAutoCommandBar(), button.eContainer());
        assertFalse(form.getItems().contains(button));
        // A usual button inside a command bar is EDT marker SU107 "Unsupported button type".
        assertEquals(ManagedFormButtonType.COMMAND_BAR_BUTTON, button.getType());
    }

    @Test
    public void moveItemAcceptsTheFormAutoCommandBarById() {
        Form form = formWithAutoCommandBar();
        Button button = button(7, "КнопкаОбновить", ManagedFormButtonType.USUAL_BUTTON); //$NON-NLS-1$
        form.getItems().add(button);

        apply(form, op("move_item", "item_id", Integer.valueOf(7), "parent_item_id", Integer.valueOf(-1))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertSame(form.getAutoCommandBar(), button.eContainer());
    }

    @Test
    public void addButtonWithoutParentLandsInTheFormAutoCommandBar() {
        Form form = formWithAutoCommandBar();
        form.getItems().add(group(2, "ГруппаШапка", ManagedFormGroupType.USUAL_GROUP)); //$NON-NLS-1$

        apply(form,
                op("add_command", "name", "Обновить", "action", "Обновить"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                op("add_button", "name", "КнопкаОбновить", "command_name", "Обновить")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        Button button = (Button) findByName(form, "КнопкаОбновить"); //$NON-NLS-1$
        assertNotNull("button must exist somewhere in the form", button); //$NON-NLS-1$
        assertSame(form.getAutoCommandBar(), button.eContainer());
        assertEquals(ManagedFormButtonType.COMMAND_BAR_BUTTON, button.getType());
    }

    @Test
    public void addButtonIntoAUsualGroupGetsAUsualButtonType() {
        Form form = formWithAutoCommandBar();
        form.getItems().add(group(2, "ГруппаКнопок", ManagedFormGroupType.USUAL_GROUP)); //$NON-NLS-1$

        apply(form,
                op("add_command", "name", "Обновить", "action", "Обновить"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                op("add_button", "name", "КнопкаОбновить", "command_name", "Обновить", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                        "parent_item_name", "ГруппаКнопок")); //$NON-NLS-1$ //$NON-NLS-2$

        Button button = (Button) findByName(form, "КнопкаОбновить"); //$NON-NLS-1$
        assertEquals(ManagedFormButtonType.USUAL_BUTTON, button.getType());
    }

    @Test
    public void movingAButtonOutOfTheCommandBarTurnsItIntoAUsualButton() {
        Form form = formWithAutoCommandBar();
        FormGroup usual = group(2, "ГруппаКнопок", ManagedFormGroupType.USUAL_GROUP); //$NON-NLS-1$
        form.getItems().add(usual);
        Button button = button(7, "КнопкаОбновить", ManagedFormButtonType.COMMAND_BAR_BUTTON); //$NON-NLS-1$
        form.getAutoCommandBar().getItems().add(button);

        apply(form, op("move_item", "item_name", "КнопкаОбновить", "parent_item_id", Integer.valueOf(2))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertSame(usual, button.eContainer());
        assertEquals(ManagedFormButtonType.USUAL_BUTTON, button.getType());
    }

    @Test
    public void itemsInsideTheAutoCommandBarCanBeUpdatedAndRemoved() {
        Form form = formWithAutoCommandBar();
        Button button = button(7, "КнопкаОбновить", ManagedFormButtonType.COMMAND_BAR_BUTTON); //$NON-NLS-1$
        form.getAutoCommandBar().getItems().add(button);

        apply(form, op("set_item", "item_name", FORM_COMMAND_BAR, "set", Map.of("autoFill", Boolean.FALSE))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(form.getAutoCommandBar().isAutoFill());

        apply(form, op("remove_item", "item_id", Integer.valueOf(7))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(form.getAutoCommandBar().getItems().isEmpty());
    }

    @Test
    public void theAutoCommandBarItselfIsNotRemovableOrMovable() {
        Form form = formWithAutoCommandBar();
        form.getItems().add(group(2, "ГруппаШапка", ManagedFormGroupType.USUAL_GROUP)); //$NON-NLS-1$

        MetadataOperationException removal = expectFailure(() -> apply(form,
                op("remove_item", "item_name", FORM_COMMAND_BAR))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(removal.getMessage(), removal.getMessage().contains(FORM_COMMAND_BAR));
        expectFailure(() -> apply(form, op("move_item", "item_name", FORM_COMMAND_BAR, "parent_item_id", Integer.valueOf(2)))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull("the bar must survive both attempts", form.getAutoCommandBar()); //$NON-NLS-1$
    }

    @Test
    public void tableAutoCommandBarIsAMoveTarget() {
        Form form = formWithAutoCommandBar();
        Table table = table(10, "Заказы"); //$NON-NLS-1$
        AutoCommandBar tableBar = FormFactory.eINSTANCE.createAutoCommandBar();
        tableBar.setId(11);
        tableBar.setName("ЗаказыКоманднаяПанель"); //$NON-NLS-1$
        table.setAutoCommandBar(tableBar);
        form.getItems().add(table);
        Button button = button(12, "КнопкаПодобрать", ManagedFormButtonType.USUAL_BUTTON); //$NON-NLS-1$
        form.getItems().add(button);

        apply(form, op("move_item", "item_id", Integer.valueOf(12), "parent_item_name", "ЗаказыКоманднаяПанель")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertSame(tableBar, button.eContainer());
        assertEquals(ManagedFormButtonType.COMMAND_BAR_BUTTON, button.getType());
    }

    /**
     * Form item ids are unique across the whole form, including the command bar, context menus
     * and extended tooltips. An id taken only from {@code getItems()} collides with them.
     */
    @Test
    public void newItemIdDoesNotCollideWithIdsOutsideGetItems() {
        Form form = formWithAutoCommandBar();
        // EDT numbers a table and its parts consecutively: an id computed from getItems() alone is
        // max(1) + 1 = 2, which is exactly the id of the table's context menu.
        Table table = table(1, "Заказы"); //$NON-NLS-1$
        ContextMenu menu = FormFactory.eINSTANCE.createContextMenu();
        menu.setId(2);
        menu.setName("ЗаказыКонтекстноеМеню"); //$NON-NLS-1$
        table.setContextMenu(menu);
        form.getItems().add(table);
        form.getAutoCommandBar().getItems().add(button(3, "КнопкаЕсть", ManagedFormButtonType.COMMAND_BAR_BUTTON)); //$NON-NLS-1$

        apply(form,
                op("add_command", "name", "Обновить", "action", "Обновить"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                op("add_button", "name", "КнопкаОбновить", "command_name", "Обновить")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        Set<Integer> ids = new HashSet<>();
        for (FormItem item : allItems(form)) {
            assertTrue("duplicate form item id " + item.getId() + " (" + item.getName() + ")", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    ids.add(Integer.valueOf(item.getId())));
        }
    }

    @Test
    public void inspectShowsTheFormAutoCommandBarWithItsButtons() throws Exception {
        Form form = formWithAutoCommandBar();
        form.getItems().add(group(2, "ГруппаШапка", ManagedFormGroupType.USUAL_GROUP)); //$NON-NLS-1$
        form.getAutoCommandBar().getItems().add(button(7, "КнопкаОбновить", ManagedFormButtonType.COMMAND_BAR_BUTTON)); //$NON-NLS-1$

        List<InspectFormLayoutResult.FormItemNode> nodes = collectFormItemNodes(form);

        InspectFormLayoutResult.FormItemNode bar = nodes.stream()
                .filter(node -> FORM_COMMAND_BAR.equals(node.name()))
                .findFirst()
                .orElse(null);
        assertNotNull("inspect_form_layout must list " + FORM_COMMAND_BAR, bar); //$NON-NLS-1$
        assertEquals(-1, bar.id());
        assertTrue(bar.kind(), bar.kind().startsWith("AutoCommandBar")); //$NON-NLS-1$
        assertEquals(List.of("КнопкаОбновить"), //$NON-NLS-1$
                bar.children().stream().map(InspectFormLayoutResult.FormItemNode::name).toList());
        assertTrue("the usual group stays listed", //$NON-NLS-1$
                nodes.stream().anyMatch(node -> "ГруппаШапка".equals(node.name()))); //$NON-NLS-1$
    }

    // ─── fixtures ───────────────────────────────────────────────────────────

    private static Form formWithAutoCommandBar() {
        Form form = FormFactory.eINSTANCE.createForm();
        AutoCommandBar bar = FormFactory.eINSTANCE.createAutoCommandBar();
        bar.setId(-1);
        bar.setName(FORM_COMMAND_BAR);
        bar.setAutoFill(true);
        bar.setVisible(true);
        form.setAutoCommandBar(bar);
        return form;
    }

    private static void standardCommands(FormStandardCommandSource source, String... enRuNames) {
        for (String pair : enRuNames) {
            String[] parts = pair.split("/"); //$NON-NLS-1$
            FormStandardCommand command = FormFactory.eINSTANCE.createFormStandardCommand();
            command.setName(parts[0]);
            command.setNameRu(parts[1]);
            source.getCommands().add(command);
        }
    }

    private static Table table(int id, String name) {
        Table table = FormFactory.eINSTANCE.createTable();
        table.setId(id);
        table.setName(name);
        table.setVisible(true);
        return table;
    }

    private static FormGroup group(int id, String name, ManagedFormGroupType type) {
        FormGroup group = FormFactory.eINSTANCE.createFormGroup();
        group.setId(id);
        group.setName(name);
        group.setType(type);
        group.setVisible(true);
        return group;
    }

    private static Button button(int id, String name, ManagedFormButtonType type) {
        Button button = FormFactory.eINSTANCE.createButton();
        button.setId(id);
        button.setName(name);
        button.setType(type);
        button.setVisible(true);
        return button;
    }

    private static Map<String, Object> op(String name, Object... keyValues) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("op", name); //$NON-NLS-1$
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            operation.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return operation;
    }

    private static List<String> names(List<FormStandardCommand> commands) {
        return commands.stream().map(FormStandardCommand::getName).toList();
    }

    private static List<FormItem> allItems(Form form) {
        List<FormItem> result = new ArrayList<>();
        for (java.util.Iterator<EObject> it = form.eAllContents(); it.hasNext();) {
            if (it.next() instanceof FormItem item) {
                result.add(item);
            }
        }
        return result;
    }

    private static FormItem findByName(Form form, String name) {
        return allItems(form).stream().filter(item -> name.equals(item.getName())).findFirst().orElse(null);
    }

    private void apply(Form form, Map<String, Object>... operations) {
        try {
            Method method = EdtMetadataService.class.getDeclaredMethod(
                    "applyFormModelOperations", Form.class, List.class, List.class); //$NON-NLS-1$
            method.setAccessible(true);
            method.invoke(service, form, List.of(operations), new ArrayList<>());
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MetadataOperationException expectFailure(Runnable action) {
        try {
            action.run();
        } catch (MetadataOperationException e) {
            return e;
        }
        fail("expected MetadataOperationException"); //$NON-NLS-1$
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<InspectFormLayoutResult.FormItemNode> collectFormItemNodes(Form form) throws Exception {
        InspectFormLayoutRequest request = new InspectFormLayoutRequest(
                "TestProject", "Form.Test", false, false, true, 0, 0); //$NON-NLS-1$ //$NON-NLS-2$
        Class<?> stateClass = Class.forName(
                "com.codepilot1c.core.edt.metadata.EdtMetadataService$FormInspectState"); //$NON-NLS-1$
        Constructor<?> stateConstructor = stateClass.getDeclaredConstructor(int.class);
        stateConstructor.setAccessible(true);
        Object state = stateConstructor.newInstance(request.effectiveMaxItems());
        Method method = EdtMetadataService.class.getDeclaredMethod("collectFormItemNodes", //$NON-NLS-1$
                FormItemContainer.class, Integer.class, String.class, int.class,
                InspectFormLayoutRequest.class, stateClass);
        method.setAccessible(true);
        return (List<InspectFormLayoutResult.FormItemNode>) method.invoke(
                service, form, null, "/Form", Integer.valueOf(0), request, state); //$NON-NLS-1$
    }
}
