package com.codepilot1c.core.edt.cmi;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.InternalEObject;

import com._1c.g5.v8.dt.cmi.model.CommandInterface;
import com._1c.g5.v8.dt.cmi.model.CommandsOrder;
import com._1c.g5.v8.dt.cmi.model.CommandsOrderFragment;
import com._1c.g5.v8.dt.cmi.model.CommandsPlacement;
import com._1c.g5.v8.dt.cmi.model.CommandsPlacementFragment;
import com._1c.g5.v8.dt.cmi.model.CommandsVisibility;
import com._1c.g5.v8.dt.cmi.model.CommandsVisibilityFragment;
import com._1c.g5.v8.dt.mcore.Command;
import com._1c.g5.v8.dt.metadata.mdclass.AdjustableBoolean;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.StandardCommand;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Model logic over an EDT command interface ({@code CommandInterface.cmi}) without BM or workspace:
 * reading its sections and removing visibility entries that name no command.
 *
 * <p>An entry without a command is what a text edit leaves behind when it strips the
 * {@code <command>} line and keeps the {@code <visibilityFragments>} wrapper: EDT then reports
 * "Команда для настройки видимости должна быть указана" on the whole interface. Such an entry
 * configures nothing, so removing it cannot change what a user sees. Two other kinds of entries are
 * reported and never pruned: a platform command ({@code 0:<uuid>}, {@code 100:<uuid>}, bare {@code 0} -
 * the event log, active users and the like; BSP hides them in its sections on purpose, and dropping
 * such an entry makes the command visible again) and an unresolved reference to a metadata object (a
 * renamed or deleted object: dropping it would hide the broken link instead of fixing it).</p>
 *
 * <p>Kept free of BM and workspace types so that it runs outside OSGi against the EDT jars:
 * see {@code tools/run-command-interface-eval.sh}.</p>
 */
public final class CommandInterfaceFragments {

    public static final String OP_PRUNE_ORPHANS = "prune_orphans"; //$NON-NLS-1$
    public static final String OP_DELETE = "delete"; //$NON-NLS-1$
    public static final List<String> OPERATIONS = List.of(OP_PRUNE_ORPHANS, OP_DELETE);

    public static final String STATE_OK = "ok"; //$NON-NLS-1$
    public static final String STATE_MISSING_COMMAND = "missing_command"; //$NON-NLS-1$
    public static final String STATE_UNRESOLVED_COMMAND = "unresolved_command"; //$NON-NLS-1$
    /**
     * A platform command referenced by its platform id ({@code 0:<uuid>}, {@code 100:<uuid>}, bare {@code 0}),
     * e.g. the event log or active users. There is no metadata object behind it, so the EDT model keeps it as an
     * unresolved proxy - but it is valid for the platform: BSP itself hides such commands in its sections.
     */
    public static final String STATE_PLATFORM_COMMAND = "platform_command"; //$NON-NLS-1$

    private static final java.util.regex.Pattern PLATFORM_COMMAND_ID = java.util.regex.Pattern.compile(
            "\\d+(:[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12})?"); //$NON-NLS-1$

    public static final String CONFIGURATION = "Configuration"; //$NON-NLS-1$
    public static final String SUBSYSTEM = "Subsystem"; //$NON-NLS-1$

    private static final String OWNER_FQN_FORMAT =
            "owner_fqn must be Configuration or Subsystem.<Name>[.Subsystem.<Name>...]"; //$NON-NLS-1$

    private CommandInterfaceFragments() {
    }

    /**
     * One visibility entry. {@code position} is 1-based, in the order of the file.
     * {@code visible} is the common flag ({@code null} when the entry carries no visibility at all),
     * {@code roleOverrides} the number of per-role values.
     */
    public record VisibilityEntry(int position, String command, String state, Boolean visible, int roleOverrides) {
    }

    /** One placement or order fragment: a command group and its commands in file order. */
    public record GroupEntry(String group, List<String> commands) {
    }

    public static List<VisibilityEntry> visibility(CommandInterface commandInterface,
            Function<EObject, String> ownerFqn) {
        CommandsVisibility section = commandInterface == null ? null : commandInterface.getCommandsVisibility();
        if (section == null) {
            return List.of();
        }
        List<VisibilityEntry> result = new ArrayList<>();
        int position = 0;
        for (CommandsVisibilityFragment fragment : section.getVisibilityFragments()) {
            position++;
            Command command = fragment.getCommand();
            AdjustableBoolean visible = fragment.getVisible();
            result.add(new VisibilityEntry(position, describeCommand(command, ownerFqn), stateOf(command),
                    visible == null ? null : Boolean.valueOf(visible.isCommon()),
                    visible == null ? 0 : visible.getFor().size()));
        }
        return result;
    }

    public static List<GroupEntry> placement(CommandInterface commandInterface, Function<EObject, String> ownerFqn) {
        CommandsPlacement section = commandInterface == null ? null : commandInterface.getCommandsPlacement();
        if (section == null) {
            return List.of();
        }
        List<GroupEntry> result = new ArrayList<>();
        for (CommandsPlacementFragment fragment : section.getPlacementFragments()) {
            result.add(new GroupEntry(describeGroup(fragment.getGroup()), describeAll(fragment.getCommands(), ownerFqn)));
        }
        return result;
    }

    public static List<GroupEntry> order(CommandInterface commandInterface, Function<EObject, String> ownerFqn) {
        CommandsOrder section = commandInterface == null ? null : commandInterface.getCommandsOrder();
        if (section == null) {
            return List.of();
        }
        List<GroupEntry> result = new ArrayList<>();
        for (CommandsOrderFragment fragment : section.getOrderFragments()) {
            result.add(new GroupEntry(describeGroup(fragment.getGroup()), describeAll(fragment.getCommands(), ownerFqn)));
        }
        return result;
    }

    /** Number of visibility entries that name no command. */
    public static int countMissingCommand(CommandInterface commandInterface) {
        CommandsVisibility section = commandInterface == null ? null : commandInterface.getCommandsVisibility();
        if (section == null) {
            return 0;
        }
        int count = 0;
        for (CommandsVisibilityFragment fragment : section.getVisibilityFragments()) {
            if (isMissingCommand(fragment)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Removes the visibility entries that name no command and returns how many were removed.
     * Entries with a command (resolved or not), placement and order are left untouched.
     */
    public static int pruneMissingCommand(CommandInterface commandInterface) {
        CommandsVisibility section = commandInterface == null ? null : commandInterface.getCommandsVisibility();
        if (section == null) {
            return 0;
        }
        List<CommandsVisibilityFragment> orphans = new ArrayList<>();
        for (CommandsVisibilityFragment fragment : section.getVisibilityFragments()) {
            if (isMissingCommand(fragment)) {
                orphans.add(fragment);
            }
        }
        if (!orphans.isEmpty()) {
            section.getVisibilityFragments().removeAll(orphans);
        }
        return orphans.size();
    }

    static boolean isMissingCommand(CommandsVisibilityFragment fragment) {
        return fragment != null && fragment.getCommand() == null;
    }

    public static String stateOf(Command command) {
        if (command == null) {
            return STATE_MISSING_COMMAND;
        }
        if (!command.eIsProxy()) {
            return STATE_OK;
        }
        return isPlatformCommandReference(command) ? STATE_PLATFORM_COMMAND : STATE_UNRESOLVED_COMMAND;
    }

    /** The reference text EDT keeps for an unresolved proxy: the last segment of its proxy URI. */
    public static String proxyReferenceText(EObject object) {
        URI uri = object instanceof InternalEObject internal ? internal.eProxyURI() : null;
        if (uri == null) {
            return null;
        }
        String text = uri.toString();
        int slash = text.lastIndexOf('/');
        return slash >= 0 ? text.substring(slash + 1) : text;
    }

    /** True for a proxy that names a platform command by its platform id, not a metadata object. */
    public static boolean isPlatformCommandReference(EObject command) {
        if (command == null || !command.eIsProxy()) {
            return false;
        }
        String text = proxyReferenceText(command);
        return text != null && PLATFORM_COMMAND_ID.matcher(text).matches();
    }

    /**
     * Renders a command the way the {@code .cmi} file names it: {@code <Owner>.Command.<Name>} or
     * {@code <Owner>.StandardCommand.<Name>}. An unresolved reference is rendered by its proxy URI.
     */
    public static String describeCommand(EObject command, Function<EObject, String> ownerFqn) {
        if (command == null) {
            return null;
        }
        if (command.eIsProxy()) {
            if (isPlatformCommandReference(command)) {
                return proxyReferenceText(command);
            }
            URI uri = command instanceof InternalEObject internal ? internal.eProxyURI() : null;
            return uri == null ? "(unresolved)" : uri.toString(); //$NON-NLS-1$
        }
        String kind = command instanceof StandardCommand ? "StandardCommand" : "Command"; //$NON-NLS-1$ //$NON-NLS-2$
        String name = nameOf(command);
        String owner = null;
        if (ownerFqn != null) {
            try {
                owner = ownerFqn.apply(command);
            } catch (RuntimeException e) {
                owner = null;
            }
        }
        String tail = kind + "." + (name == null ? "?" : name); //$NON-NLS-1$ //$NON-NLS-2$
        return owner == null || owner.isBlank() ? tail : owner + "." + tail; //$NON-NLS-1$
    }

    /** Standard groups are named as in the file ({@code NavigationPanelOrdinary}); own groups get their kind. */
    public static String describeGroup(EObject group) {
        if (group == null) {
            return null;
        }
        String name = nameOf(group);
        if (group.eIsProxy()) {
            URI uri = group instanceof InternalEObject internal ? internal.eProxyURI() : null;
            return uri == null ? "(unresolved)" : uri.toString(); //$NON-NLS-1$
        }
        return group instanceof MdObject ? "CommandGroup." + name : name; //$NON-NLS-1$
    }

    public static String nameOf(EObject object) {
        if (object == null || object.eClass() == null) {
            return null;
        }
        EStructuralFeature feature = object.eClass().getEStructuralFeature("name"); //$NON-NLS-1$
        if (feature == null) {
            return null;
        }
        Object value = object.eGet(feature);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Normalizes an owner FQN: {@code Configuration} or {@code Subsystem.<Name>[.Subsystem.<Name>...]};
     * the Russian forms EDT prints in diagnostics ({@code Конфигурация}, {@code Подсистема.X.Подсистема.Y},
     * also with a trailing {@code .КомандныйИнтерфейс}/{@code .CommandInterface}) are accepted.
     */
    public static String normalizeOwnerFqn(String ownerFqn) {
        if (ownerFqn == null || ownerFqn.isBlank()) {
            throw invalidOwner("owner_fqn is required"); //$NON-NLS-1$
        }
        String[] raw = ownerFqn.trim().split("\\.", -1); //$NON-NLS-1$
        int length = raw.length;
        String first = raw[0].trim().toLowerCase(Locale.ROOT);
        if ("configuration".equals(first) || "конфигурация".equals(first)) { //$NON-NLS-1$ //$NON-NLS-2$
            if (length == 1 || (length == 2 && isCommandInterfaceSuffix(raw[1]))) {
                return CONFIGURATION;
            }
            throw invalidOwner(OWNER_FQN_FORMAT + ": " + ownerFqn); //$NON-NLS-1$
        }
        // The suffix is only meaningful after complete marker/name pairs: in an even-length FQN the
        // last segment is a name, and a subsystem may well be called CommandInterface.
        if (length % 2 == 1 && isCommandInterfaceSuffix(raw[length - 1])) {
            length--;
        }
        if (length < 2 || length % 2 != 0) {
            throw invalidOwner(OWNER_FQN_FORMAT + ": " + ownerFqn); //$NON-NLS-1$
        }
        StringBuilder canonical = new StringBuilder();
        for (int i = 0; i < length; i += 2) {
            String marker = raw[i].trim().toLowerCase(Locale.ROOT);
            String name = raw[i + 1].trim();
            if (!("subsystem".equals(marker) || "подсистема".equals(marker)) || name.isEmpty()) { //$NON-NLS-1$ //$NON-NLS-2$
                throw invalidOwner(OWNER_FQN_FORMAT + ": " + ownerFqn); //$NON-NLS-1$
            }
            if (canonical.length() > 0) {
                canonical.append('.');
            }
            canonical.append(SUBSYSTEM).append('.').append(name);
        }
        return canonical.toString();
    }

    /** Returns the operation name if it is supported, otherwise refuses with the list of supported ones. */
    public static String requireKnownOperation(Object op) {
        String name = op == null ? "" : String.valueOf(op).trim().toLowerCase(Locale.ROOT); //$NON-NLS-1$
        if (!OPERATIONS.contains(name)) {
            throw new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "Unsupported command interface operation: '" + op + "'. Supported: " + OPERATIONS, false); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return name;
    }

    private static List<String> describeAll(List<? extends EObject> commands, Function<EObject, String> ownerFqn) {
        List<String> result = new ArrayList<>();
        for (EObject command : commands) {
            result.add(describeCommand(command, ownerFqn));
        }
        return result;
    }

    private static boolean isCommandInterfaceSuffix(String segment) {
        String value = segment == null ? "" : segment.trim().toLowerCase(Locale.ROOT); //$NON-NLS-1$
        return "commandinterface".equals(value) || "командныйинтерфейс".equals(value); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static MetadataOperationException invalidOwner(String message) {
        return new MetadataOperationException(MetadataOperationCode.INVALID_PROPERTY_VALUE, message, false);
    }
}
