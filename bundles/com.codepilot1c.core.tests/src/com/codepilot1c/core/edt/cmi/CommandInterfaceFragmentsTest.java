package com.codepilot1c.core.edt.cmi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;
import org.junit.Test;

import com._1c.g5.v8.dt.cmi.model.CmiFactory;
import com._1c.g5.v8.dt.cmi.model.CommandInterface;
import com._1c.g5.v8.dt.cmi.model.CommandsOrder;
import com._1c.g5.v8.dt.cmi.model.CommandsOrderFragment;
import com._1c.g5.v8.dt.cmi.model.CommandsPlacement;
import com._1c.g5.v8.dt.cmi.model.CommandsPlacementFragment;
import com._1c.g5.v8.dt.cmi.model.CommandsVisibility;
import com._1c.g5.v8.dt.cmi.model.CommandsVisibilityFragment;
import com._1c.g5.v8.dt.mcore.Command;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.StandardCommandGroup;
import com._1c.g5.v8.dt.metadata.mdclass.AdjustableBoolean;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessorCommand;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.StandardCommand;
import com.codepilot1c.core.edt.cmi.CommandInterfaceFragments.GroupEntry;
import com.codepilot1c.core.edt.cmi.CommandInterfaceFragments.VisibilityEntry;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Paired eval for {@link CommandInterfaceFragments} on the real EDT command interface model.
 *
 * <p>The fixture mirrors a real subsystem command interface damaged by a text edit: sixteen visibility
 * entries, four of them without a command (positions 1, 2, 13, 14), six data processors in
 * NavigationPanelOrdinary and an emptied NavigationPanelImportant. Every positive assertion has a
 * negative twin: a pruner that cannot refuse proves nothing.</p>
 *
 * <p>Runs outside OSGi against the EDT jars: see {@code tools/run-command-interface-eval.sh}.</p>
 */
public class CommandInterfaceFragmentsTest {

    private static final String[] PROCESSORS = {
            "ИнформацияОЗаказах", "УправлениеСкладом", "УпаковкаЗаказов", "ПриемТовараНаСклад",
            "ОтгрузкаЗаказов", "ИдентификацияТоваров"
    };
    private static final String[] OPENED_FIRST = {
            "ИдентификацияТоваров", "ИнформацияОЗаказах", "ОтгрузкаЗаказов", "ПриемТовараНаСклад"
    };
    private static final String[] OPENED_LAST = {"УпаковкаЗаказов", "УправлениеСкладом"};

    /**
     * Owner FQN the way BM renders the top object of a command: DataProcessor.<Name>. Detached test
     * objects have no top object, so the processor name comes from the command itself (see fixture).
     */
    private static final Function<EObject, String> OWNER = command -> "DataProcessor." + ownerName(command); //$NON-NLS-1$

    // ------------------------------------------------------------------ counting and pruning

    @Test
    public void countsOnlyEntriesWithoutCommand() {
        CommandInterface ci = realisticInterface();
        assertEquals(4, CommandInterfaceFragments.countMissingCommand(ci));
        assertEquals(16, ci.getCommandsVisibility().getVisibilityFragments().size());
    }

    @Test
    public void pruneRemovesExactlyTheEntriesWithoutCommandAndKeepsTheOrder() {
        CommandInterface ci = realisticInterface();
        List<String> before = commandsInVisibility(ci);

        assertEquals(4, CommandInterfaceFragments.pruneMissingCommand(ci));

        List<CommandsVisibilityFragment> left = ci.getCommandsVisibility().getVisibilityFragments();
        assertEquals(12, left.size());
        for (CommandsVisibilityFragment fragment : left) {
            assertNotNull("an entry with a command was removed or an orphan survived", fragment.getCommand());
        }
        List<String> expected = new ArrayList<>(before);
        expected.removeIf(value -> value == null);
        assertEquals("entries with commands must keep their order", expected, commandsInVisibility(ci));
        assertEquals(0, CommandInterfaceFragments.countMissingCommand(ci));
    }

    @Test
    public void pruneKeepsVisibilityValuesOfTheRemainingEntries() {
        CommandInterface ci = realisticInterface();
        CommandInterfaceFragments.pruneMissingCommand(ci);
        List<VisibilityEntry> entries = CommandInterfaceFragments.visibility(ci, OWNER);
        // the six processor commands are visible, the six Open standard commands are not
        for (int i = 0; i < 6; i++) {
            assertEquals(Boolean.TRUE, entries.get(i).visible());
        }
        for (int i = 6; i < 12; i++) {
            assertEquals(Boolean.FALSE, entries.get(i).visible());
        }
    }

    @Test
    public void pruneDoesNotTouchPlacementAndOrder() {
        CommandInterface ci = realisticInterface();
        List<GroupEntry> placementBefore = CommandInterfaceFragments.placement(ci, OWNER);
        List<GroupEntry> orderBefore = CommandInterfaceFragments.order(ci, OWNER);

        CommandInterfaceFragments.pruneMissingCommand(ci);

        assertEquals(placementBefore, CommandInterfaceFragments.placement(ci, OWNER));
        assertEquals(orderBefore, CommandInterfaceFragments.order(ci, OWNER));
        assertEquals(2, placementBefore.size());
        assertEquals("NavigationPanelImportant", placementBefore.get(0).group());
        assertTrue(placementBefore.get(0).commands().isEmpty());
        assertEquals(6, placementBefore.get(1).commands().size());
    }

    @Test
    public void pruneIsIdempotent() {
        CommandInterface ci = realisticInterface();
        assertEquals(4, CommandInterfaceFragments.pruneMissingCommand(ci));
        assertEquals(0, CommandInterfaceFragments.pruneMissingCommand(ci));
        assertEquals(12, ci.getCommandsVisibility().getVisibilityFragments().size());
    }

    @Test
    public void cleanInterfaceIsLeftAlone() {
        CommandInterface ci = realisticInterface();
        CommandInterfaceFragments.pruneMissingCommand(ci);
        List<String> clean = commandsInVisibility(ci);
        assertEquals(0, CommandInterfaceFragments.pruneMissingCommand(ci));
        assertEquals(clean, commandsInVisibility(ci));
    }

    // ------------------------------------------------------------------ unresolved references

    @Test
    public void unresolvedCommandIsReportedButNeverPruned() {
        CommandInterface ci = realisticInterface();
        DataProcessorCommand broken = MdClassFactory.eINSTANCE.createDataProcessorCommand();
        ((InternalEObject) broken).eSetProxyURI(
                URI.createURI("bm://demo/DataProcessor.Удаленная.Command.Удаленная")); //$NON-NLS-1$
        ci.getCommandsVisibility().getVisibilityFragments().add(fragment(broken, true));

        assertEquals("an unresolved reference is not a missing command",
                4, CommandInterfaceFragments.countMissingCommand(ci));
        VisibilityEntry last = lastEntry(ci);
        assertEquals(CommandInterfaceFragments.STATE_UNRESOLVED_COMMAND, last.state());
        assertTrue(last.command(), last.command().contains("Удаленная")); //$NON-NLS-1$

        assertEquals(4, CommandInterfaceFragments.pruneMissingCommand(ci));
        assertEquals("the unresolved entry must survive the prune",
                13, ci.getCommandsVisibility().getVisibilityFragments().size());
        assertEquals(CommandInterfaceFragments.STATE_UNRESOLVED_COMMAND, lastEntry(ci).state());
    }

    // ------------------------------------------------------------------ platform commands

    @Test
    public void platformCommandsAreRecognizedAndNeverPruned() {
        // BSP 3.1.12.238 hides platform commands in its Администрирование section exactly like this:
        // <command>0:<uuid></command>, <command>100:<uuid></command> and a bare <command>0</command>.
        CommandInterface ci = realisticInterface();
        List<CommandsVisibilityFragment> entries = ci.getCommandsVisibility().getVisibilityFragments();
        entries.add(fragment(proxy("unresolved:/0:b8a966ba-3452-44df-91d0-7040ecd3e4c2"), false)); //$NON-NLS-1$
        entries.add(fragment(proxy("unresolved:/100:5e01ee62-d690-42bd-997e-491c90ea1c56"), false)); //$NON-NLS-1$
        entries.add(fragment(proxy("unresolved:/0"), false)); //$NON-NLS-1$

        List<VisibilityEntry> visibility = CommandInterfaceFragments.visibility(ci, OWNER);
        List<VisibilityEntry> platform = visibility.subList(visibility.size() - 3, visibility.size());
        for (VisibilityEntry entry : platform) {
            assertEquals(CommandInterfaceFragments.STATE_PLATFORM_COMMAND, entry.state());
        }
        assertEquals("0:b8a966ba-3452-44df-91d0-7040ecd3e4c2", platform.get(0).command()); //$NON-NLS-1$
        assertEquals("100:5e01ee62-d690-42bd-997e-491c90ea1c56", platform.get(1).command()); //$NON-NLS-1$
        assertEquals("0", platform.get(2).command()); //$NON-NLS-1$

        assertEquals(4, CommandInterfaceFragments.pruneMissingCommand(ci));
        assertEquals("platform commands must survive the prune: dropping the entry shows the command again",
                15, ci.getCommandsVisibility().getVisibilityFragments().size());
    }

    @Test
    public void brokenMetadataReferenceIsNotMistakenForPlatformCommand() {
        assertEquals(CommandInterfaceFragments.STATE_UNRESOLVED_COMMAND, CommandInterfaceFragments.stateOf(
                proxy("unresolved:/DataProcessor.Удаленная.Command.Удаленная"))); //$NON-NLS-1$
        assertFalse(CommandInterfaceFragments.isPlatformCommandReference(
                proxy("unresolved:/Catalog.Номенклатура.StandardCommand.OpenList"))); //$NON-NLS-1$
        assertFalse(CommandInterfaceFragments.isPlatformCommandReference(proxy("unresolved:/0:not-a-uuid"))); //$NON-NLS-1$
        assertFalse(CommandInterfaceFragments.isPlatformCommandReference(processorCommand("Команда1"))); //$NON-NLS-1$
        assertFalse(CommandInterfaceFragments.isPlatformCommandReference(null));
    }

    // ------------------------------------------------------------------ reporting

    @Test
    public void visibilityReportsPositionsStatesFlagsAndCommandNames() {
        List<VisibilityEntry> entries = CommandInterfaceFragments.visibility(realisticInterface(), OWNER);
        assertEquals(16, entries.size());
        List<Integer> missing = new ArrayList<>();
        for (VisibilityEntry entry : entries) {
            if (CommandInterfaceFragments.STATE_MISSING_COMMAND.equals(entry.state())) {
                missing.add(Integer.valueOf(entry.position()));
                assertNull(entry.command());
            } else {
                assertEquals(CommandInterfaceFragments.STATE_OK, entry.state());
                assertNotNull(entry.command());
            }
        }
        assertEquals(List.of(1, 2, 13, 14), missing);
        assertEquals(Boolean.TRUE, entries.get(0).visible());
        assertEquals(Boolean.FALSE, entries.get(12).visible());
        assertEquals("DataProcessor.ИнформацияОЗаказах.Command.ИнформацияОЗаказах", entries.get(2).command()); //$NON-NLS-1$
        assertEquals("DataProcessor.ИдентификацияТоваров.StandardCommand.Open", entries.get(8).command()); //$NON-NLS-1$
    }

    @Test
    public void rendersCommandWithoutOwnerAndGroups() {
        DataProcessorCommand command = MdClassFactory.eINSTANCE.createDataProcessorCommand();
        command.setName("Команда1"); //$NON-NLS-1$
        assertEquals("Command.Команда1", CommandInterfaceFragments.describeCommand(command, null)); //$NON-NLS-1$
        assertNull(CommandInterfaceFragments.describeCommand(null, OWNER));
        assertEquals("NavigationPanelOrdinary", //$NON-NLS-1$
                CommandInterfaceFragments.describeGroup(standardGroup("NavigationPanelOrdinary"))); //$NON-NLS-1$
        com._1c.g5.v8.dt.metadata.mdclass.CommandGroup own = MdClassFactory.eINSTANCE.createCommandGroup();
        own.setName("СервисныеОбработки"); //$NON-NLS-1$
        assertEquals("CommandGroup.СервисныеОбработки", CommandInterfaceFragments.describeGroup(own)); //$NON-NLS-1$
    }

    @Test
    public void emptyAndAbsentInterfacesAreSafe() {
        CommandInterface empty = CmiFactory.eINSTANCE.createCommandInterface();
        assertEquals(0, CommandInterfaceFragments.countMissingCommand(empty));
        assertEquals(0, CommandInterfaceFragments.pruneMissingCommand(empty));
        assertTrue(CommandInterfaceFragments.visibility(empty, OWNER).isEmpty());
        assertTrue(CommandInterfaceFragments.placement(empty, OWNER).isEmpty());
        assertEquals(0, CommandInterfaceFragments.countMissingCommand(null));
        assertEquals(0, CommandInterfaceFragments.pruneMissingCommand(null));
    }

    // ------------------------------------------------------------------ owner FQN and operations

    @Test
    public void normalizesOwnerFqnForms() {
        assertEquals("Subsystem.Служебная.Subsystem.Обработки", //$NON-NLS-1$
                CommandInterfaceFragments.normalizeOwnerFqn("Subsystem.Служебная.Subsystem.Обработки")); //$NON-NLS-1$
        assertEquals("Subsystem.Служебная.Subsystem.Обработки", //$NON-NLS-1$
                CommandInterfaceFragments.normalizeOwnerFqn(" Подсистема.Служебная.Подсистема.Обработки.КомандныйИнтерфейс ")); //$NON-NLS-1$
        assertEquals("Subsystem.Приемка", CommandInterfaceFragments.normalizeOwnerFqn("Subsystem.Приемка.CommandInterface")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Configuration", CommandInterfaceFragments.normalizeOwnerFqn("Конфигурация")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Configuration", CommandInterfaceFragments.normalizeOwnerFqn("Configuration.CommandInterface")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void subsystemNamedCommandInterfaceIsNotMistakenForTheSuffix() {
        assertEquals("Subsystem.CommandInterface", //$NON-NLS-1$
                CommandInterfaceFragments.normalizeOwnerFqn("Subsystem.CommandInterface")); //$NON-NLS-1$
        assertEquals("Subsystem.А.Subsystem.CommandInterface", //$NON-NLS-1$
                CommandInterfaceFragments.normalizeOwnerFqn("Subsystem.А.Subsystem.CommandInterface")); //$NON-NLS-1$
    }

    @Test
    public void refusesOwnersThatAreNotConfigurationOrSubsystem() {
        for (String bad : new String[] {null, "", "  ", "Subsystem", "Subsystem.А.Subsystem", "Catalog.Номенклатура", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "Subsystem.А.Catalog.Б", "Configuration.Роли", "Subsystem..Subsystem.Б", "КомандныйИнтерфейс"}) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            try {
                CommandInterfaceFragments.normalizeOwnerFqn(bad);
                fail("accepted malformed owner: " + bad); //$NON-NLS-1$
            } catch (MetadataOperationException e) {
                assertEquals(MetadataOperationCode.INVALID_PROPERTY_VALUE, e.getCode());
            }
        }
    }

    @Test
    public void acceptsOnlyKnownOperations() {
        assertEquals("prune_orphans", CommandInterfaceFragments.requireKnownOperation(" Prune_Orphans ")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("delete", CommandInterfaceFragments.requireKnownOperation("delete")); //$NON-NLS-1$ //$NON-NLS-2$
        for (Object bad : new Object[] {null, "", "remove_command", "prune"}) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            try {
                CommandInterfaceFragments.requireKnownOperation(bad);
                fail("accepted unknown operation: " + bad); //$NON-NLS-1$
            } catch (MetadataOperationException e) {
                assertEquals(MetadataOperationCode.INVALID_METADATA_CHANGE, e.getCode());
            }
        }
        assertFalse(CommandInterfaceFragments.OPERATIONS.isEmpty());
    }

    // ------------------------------------------------------------------ fixture

    private static CommandInterface realisticInterface() {
        CmiFactory cmi = CmiFactory.eINSTANCE;
        CommandInterface ci = cmi.createCommandInterface();
        CommandsVisibility visibility = cmi.createCommandsVisibility();
        ci.setCommandsVisibility(visibility);
        List<CommandsVisibilityFragment> entries = visibility.getVisibilityFragments();
        entries.add(fragment(null, true));
        entries.add(fragment(null, true));
        List<DataProcessorCommand> commands = new ArrayList<>();
        for (String name : PROCESSORS) {
            DataProcessorCommand command = processorCommand(name);
            commands.add(command);
            entries.add(fragment(command, true));
        }
        for (String name : OPENED_FIRST) {
            entries.add(fragment(open(name), false));
        }
        entries.add(fragment(null, false));
        entries.add(fragment(null, false));
        for (String name : OPENED_LAST) {
            entries.add(fragment(open(name), false));
        }

        CommandsPlacement placement = cmi.createCommandsPlacement();
        ci.setCommandsPlacement(placement);
        CommandsPlacementFragment important = cmi.createCommandsPlacementFragment();
        important.setGroup(standardGroup("NavigationPanelImportant")); //$NON-NLS-1$
        placement.getPlacementFragments().add(important);
        CommandsPlacementFragment ordinary = cmi.createCommandsPlacementFragment();
        ordinary.setGroup(standardGroup("NavigationPanelOrdinary")); //$NON-NLS-1$
        ordinary.getCommands().addAll(commands);
        placement.getPlacementFragments().add(ordinary);

        CommandsOrder order = cmi.createCommandsOrder();
        ci.setCommandsOrder(order);
        CommandsOrderFragment importantOrder = cmi.createCommandsOrderFragment();
        importantOrder.setGroup(standardGroup("NavigationPanelImportant")); //$NON-NLS-1$
        order.getOrderFragments().add(importantOrder);
        CommandsOrderFragment ordinaryOrder = cmi.createCommandsOrderFragment();
        ordinaryOrder.setGroup(standardGroup("NavigationPanelOrdinary")); //$NON-NLS-1$
        ordinaryOrder.getCommands().addAll(commands);
        order.getOrderFragments().add(ordinaryOrder);
        return ci;
    }

    private static CommandsVisibilityFragment fragment(Command command, boolean visible) {
        CommandsVisibilityFragment fragment = CmiFactory.eINSTANCE.createCommandsVisibilityFragment();
        fragment.setCommand(command);
        AdjustableBoolean flag = MdClassFactory.eINSTANCE.createAdjustableBoolean();
        flag.setCommon(visible);
        fragment.setVisible(flag);
        return fragment;
    }

    private static DataProcessorCommand proxy(String uri) {
        DataProcessorCommand command = MdClassFactory.eINSTANCE.createDataProcessorCommand();
        ((InternalEObject) command).eSetProxyURI(URI.createURI(uri));
        return command;
    }

    private static DataProcessorCommand processorCommand(String name) {
        DataProcessorCommand command = MdClassFactory.eINSTANCE.createDataProcessorCommand();
        command.setName(name);
        return command;
    }

    /** Standard command Open; the owner name is kept in its Russian name slot for the test renderer. */
    private static StandardCommand open(String owner) {
        StandardCommand command = MdClassFactory.eINSTANCE.createStandardCommand();
        command.setName("Open"); //$NON-NLS-1$
        command.setNameRu(owner);
        return command;
    }

    private static String ownerOfStandard(StandardCommand command) {
        return command.getNameRu();
    }

    private static String ownerName(EObject command) {
        return command instanceof DataProcessorCommand dp ? dp.getName() : ownerOfStandard((StandardCommand) command);
    }

    private static StandardCommandGroup standardGroup(String name) {
        StandardCommandGroup group = McoreFactory.eINSTANCE.createStandardCommandGroup();
        group.setName(name);
        return group;
    }

    private static List<String> commandsInVisibility(CommandInterface ci) {
        List<String> result = new ArrayList<>();
        for (VisibilityEntry entry : CommandInterfaceFragments.visibility(ci, OWNER)) {
            result.add(entry.command());
        }
        return result;
    }

    private static VisibilityEntry lastEntry(CommandInterface ci) {
        List<VisibilityEntry> entries = CommandInterfaceFragments.visibility(ci, OWNER);
        return entries.get(entries.size() - 1);
    }
}
