package com.codepilot1c.core.edt.forms;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.emf.ecore.EReference;

import com._1c.g5.v8.dt.form.model.FormStandardCommand;
import com._1c.g5.v8.dt.form.model.FormStandardCommandSource;
import com._1c.g5.v8.dt.mcore.NamedElement;

import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Writes {@code excludedCommands} of a form or of a table: the standard commands the platform must
 * not show.
 *
 * <p>The property is a non-containment reference list. Its elements are the source's own
 * {@link FormStandardCommand} objects from {@link FormStandardCommandSource#getCommands()} — the
 * list EDT computes from the form's main attribute or the table's data — and they are serialized
 * into {@code Form.form} by name ({@code <excludedCommands>Copy</excludedCommands>}). This is the
 * same pair EDT's own editor uses ({@code BmFormCommandSetModel}). A detached command built from a
 * name would not resolve, so names are resolved against that list, and an unknown name is rejected
 * instead of being dropped: a silently skipped exclusion leaves a working "Delete" button on a
 * read-only form.</p>
 */
public final class FormStandardCommandExclusions {

    /** Model name of the property. */
    public static final String PROPERTY = "excludedCommands"; //$NON-NLS-1$

    private FormStandardCommandExclusions() {
        // static helpers only
    }

    /** Whether the reference is the {@code excludedCommands} list of a standard command source. */
    public static boolean isExcludedCommands(EReference reference) {
        return reference != null && PROPERTY.equals(reference.getName()) && reference.isMany()
                && FormStandardCommandSource.class.isAssignableFrom(reference.getEContainingClass().getInstanceClass());
    }

    /**
     * Replaces the exclusions of {@code source} with the standard commands named in {@code value}.
     *
     * @param source form or table
     * @param value list of command names (English or Russian, case-insensitive), a single name, a
     *        comma-separated string; {@code null} or an empty list clears the exclusions
     * @param fieldName property name as the caller spelled it, for messages
     * @return English names of the commands now excluded, in the source's command order
     */
    public static List<String> apply(FormStandardCommandSource source, Object value, String fieldName) {
        List<String> requested = requestedNames(value);
        List<FormStandardCommand> available = source.getCommands();
        List<FormStandardCommand> resolved = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String name : requested) {
            FormStandardCommand command = find(available, name);
            if (command == null) {
                unknown.add(name);
            } else if (!resolved.contains(command)) {
                resolved.add(command);
            }
        }
        if (!unknown.isEmpty()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_PROPERTY_VALUE,
                    unknownMessage(source, fieldName, unknown, available),
                    false);
        }
        // Source order, not request order: the result does not depend on how the caller listed it.
        List<FormStandardCommand> ordered = new ArrayList<>();
        for (FormStandardCommand command : available) {
            if (resolved.contains(command)) {
                ordered.add(command);
            }
        }
        source.getExcludedCommands().clear();
        source.getExcludedCommands().addAll(ordered);
        return ordered.stream().map(FormStandardCommand::getName).toList();
    }

    private static List<String> requestedNames(Object value) {
        List<String> names = new ArrayList<>();
        if (value == null) {
            return names;
        }
        if (value instanceof Collection<?> collection) {
            for (Object entry : collection) {
                if (entry != null) {
                    addNames(names, String.valueOf(entry));
                }
            }
            return names;
        }
        addNames(names, String.valueOf(value));
        return names;
    }

    private static void addNames(List<String> names, String raw) {
        for (String token : raw.split("[,;]")) { //$NON-NLS-1$
            String name = token.trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
    }

    private static FormStandardCommand find(List<FormStandardCommand> commands, String name) {
        for (FormStandardCommand command : commands) {
            if (command != null
                    && (name.equalsIgnoreCase(command.getName()) || name.equalsIgnoreCase(command.getNameRu()))) {
                return command;
            }
        }
        return null;
    }

    private static String unknownMessage(FormStandardCommandSource source, String fieldName, List<String> unknown,
            List<FormStandardCommand> available) {
        String owner = source.eClass().getName()
                + (source instanceof NamedElement named && named.getName() != null ? " '" + named.getName() + "'" : ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        StringBuilder message = new StringBuilder()
                .append(fieldName).append(": unknown standard command(s) of ").append(owner).append(": ") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .append(String.join(", ", unknown)).append(". "); //$NON-NLS-1$ //$NON-NLS-2$
        if (available.isEmpty()) {
            return message.append("The source exposes no standard commands: EDT has not computed them yet ") //$NON-NLS-1$
                    .append("(derived data of a just-created or still indexing form) or the source has none. ") //$NON-NLS-1$
                    .append("Nothing was changed.").toString(); //$NON-NLS-1$
        }
        List<String> names = new ArrayList<>();
        for (FormStandardCommand command : available) {
            names.add(command.getNameRu() == null || command.getNameRu().isBlank()
                    ? command.getName()
                    : command.getName() + " (" + command.getNameRu() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return message.append("Available: ").append(String.join(", ", names)).append(". Nothing was changed.") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .toString();
    }
}
