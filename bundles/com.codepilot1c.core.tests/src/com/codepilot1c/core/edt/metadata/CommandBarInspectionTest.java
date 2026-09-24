package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.AutoCommandBar;
import com._1c.g5.v8.dt.form.model.Button;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormStandardCommand;
import com._1c.g5.v8.dt.form.model.Table;

import com.codepilot1c.core.edt.forms.EventHandlerCatalog;
import com.codepilot1c.core.edt.forms.EventHandlerTargetResolver;
import com.codepilot1c.core.edt.forms.InspectFormLayoutRequest;
import com.codepilot1c.core.edt.forms.InspectFormLayoutResult;
import com.codepilot1c.core.edt.forms.InspectFormLayoutResult.CommandBarNode;

/**
 * Covers {@code EdtMetadataService#collectCommandBarNodes}: surfacing built-in,
 * reference-backed command bar state (autoCommandBar/excludedCommands) that ordinary
 * {@code FormItemContainer.getItems()} traversal cannot see, distinct from items[].
 */
public class CommandBarInspectionTest {

    private EdtMetadataService service;

    @Before
    public void setUp() {
        EventHandlerCatalog emptyCatalog = item -> List.of();
        service = new EdtMetadataService(new EdtMetadataGateway(), new EventHandlerTargetResolver(emptyCatalog));
    }

    @Test
    public void formRootWithNoAutoCommandBarProducesNoCommandBarNodes() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();

        List<CommandBarNode> nodes = collectCommandBarNodes(form, defaultRequest());

        assertTrue(nodes.isEmpty());
    }

    @Test
    public void formRootAutoCommandBarIsReportedDistinctFromItems() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        AutoCommandBar autoBar = FormFactory.eINSTANCE.createAutoCommandBar();
        autoBar.setAutoFill(true);
        form.setAutoCommandBar(autoBar);

        List<CommandBarNode> nodes = collectCommandBarNodes(form, defaultRequest());

        assertEquals(1, nodes.size());
        CommandBarNode node = nodes.get(0);
        assertNull(node.ownerItemId());
        assertEquals("FORM", node.ownerKind()); //$NON-NLS-1$
        assertEquals("AUTO_COMMAND_BAR", node.kind()); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, node.autoFill());
        assertTrue(node.excludedCommands().isEmpty());
        assertTrue(node.items().isEmpty());
    }

    @Test
    public void tableAutoCommandBarIsReportedWithOwnerItemIdAndName() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        Table table = FormFactory.eINSTANCE.createTable();
        table.setId(1);
        table.setName("List"); //$NON-NLS-1$
        AutoCommandBar autoBar = FormFactory.eINSTANCE.createAutoCommandBar();
        autoBar.setAutoFill(false);
        table.setAutoCommandBar(autoBar);
        form.getItems().add(table);

        List<CommandBarNode> nodes = collectCommandBarNodes(form, defaultRequest());

        assertEquals(1, nodes.size());
        CommandBarNode node = nodes.get(0);
        assertEquals(Integer.valueOf(1), node.ownerItemId());
        assertEquals("List", node.ownerItemName()); //$NON-NLS-1$
        assertEquals("Table", node.ownerKind()); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, node.autoFill());
    }

    @Test
    public void excludedAndAvailableCommandNamesAreSurfaced() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        Table table = FormFactory.eINSTANCE.createTable();
        table.setId(1);
        table.setName("List"); //$NON-NLS-1$
        table.setAutoCommandBar(FormFactory.eINSTANCE.createAutoCommandBar());
        FormStandardCommand add = standardCommand("Add"); //$NON-NLS-1$
        FormStandardCommand delete = standardCommand("Delete"); //$NON-NLS-1$
        table.getCommands().add(add);
        table.getCommands().add(delete);
        table.getExcludedCommands().add(delete);
        form.getItems().add(table);

        List<CommandBarNode> nodes = collectCommandBarNodes(form, defaultRequest());

        assertEquals(1, nodes.size());
        CommandBarNode node = nodes.get(0);
        assertEquals(List.of("Delete"), node.excludedCommands()); //$NON-NLS-1$
        assertEquals(List.of("Add", "Delete"), node.availableCommands()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void excludedCommandsAloneWithoutAutoCommandBarIsStillDiscovered() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        Table table = FormFactory.eINSTANCE.createTable();
        table.setId(1);
        table.setName("List"); //$NON-NLS-1$
        // No autoCommandBar set — but an excluded command was still recorded on the model.
        FormStandardCommand delete = standardCommand("Delete"); //$NON-NLS-1$
        table.getCommands().add(delete);
        table.getExcludedCommands().add(delete);
        form.getItems().add(table);

        List<CommandBarNode> nodes = collectCommandBarNodes(form, defaultRequest());

        assertEquals(1, nodes.size());
        assertNull(nodes.get(0).autoFill());
        assertEquals(List.of("Delete"), nodes.get(0).excludedCommands()); //$NON-NLS-1$
    }

    @Test
    public void autoCommandBarButtonsAreSurfacedAsFormItemNodesDistinctFromOrdinaryItems() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        Table table = FormFactory.eINSTANCE.createTable();
        table.setId(1);
        table.setName("List"); //$NON-NLS-1$
        AutoCommandBar autoBar = FormFactory.eINSTANCE.createAutoCommandBar();
        Button button = FormFactory.eINSTANCE.createButton();
        button.setId(2);
        button.setName("Add"); //$NON-NLS-1$
        autoBar.getItems().add(button);
        table.setAutoCommandBar(autoBar);
        form.getItems().add(table);

        List<CommandBarNode> nodes = collectCommandBarNodes(form, defaultRequest());

        assertEquals(1, nodes.size());
        List<InspectFormLayoutResult.FormItemNode> items = nodes.get(0).items();
        assertEquals(1, items.size());
        assertEquals("Add", items.get(0).name()); //$NON-NLS-1$
        assertEquals(2, items.get(0).id());
    }

    @Test
    public void nestedTableInsideGroupIsStillDiscovered() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        com._1c.g5.v8.dt.form.model.FormGroup group = FormFactory.eINSTANCE.createFormGroup();
        group.setId(1);
        group.setName("Group"); //$NON-NLS-1$
        group.setType(com._1c.g5.v8.dt.form.model.ManagedFormGroupType.USUAL_GROUP);
        Table table = FormFactory.eINSTANCE.createTable();
        table.setId(2);
        table.setName("Nested"); //$NON-NLS-1$
        AutoCommandBar autoBar = FormFactory.eINSTANCE.createAutoCommandBar();
        autoBar.setAutoFill(true);
        table.setAutoCommandBar(autoBar);
        group.getItems().add(table);
        form.getItems().add(group);

        List<CommandBarNode> nodes = collectCommandBarNodes(form, defaultRequest());

        assertEquals(1, nodes.size());
        assertEquals(Integer.valueOf(2), nodes.get(0).ownerItemId());
        assertEquals("Nested", nodes.get(0).ownerItemName()); //$NON-NLS-1$
    }

    private static FormStandardCommand standardCommand(String name) {
        FormStandardCommand command = FormFactory.eINSTANCE.createFormStandardCommand();
        command.setName(name);
        return command;
    }

    private static InspectFormLayoutRequest defaultRequest() {
        return new InspectFormLayoutRequest("Project", "Catalog.C.Form.F", true, true, true, 0, 0); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @SuppressWarnings("unchecked")
    private List<CommandBarNode> collectCommandBarNodes(Form formModel, InspectFormLayoutRequest request)
            throws Exception {
        Method method = EdtMetadataService.class.getDeclaredMethod(
                "collectCommandBarNodes", Form.class, String.class, InspectFormLayoutRequest.class); //$NON-NLS-1$
        method.setAccessible(true);
        return (List<CommandBarNode>) method.invoke(service, formModel, "/Form", request); //$NON-NLS-1$
    }
}
