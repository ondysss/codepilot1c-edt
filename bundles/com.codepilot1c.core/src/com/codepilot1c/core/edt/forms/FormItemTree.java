package com.codepilot1c.core.edt.forms;

import java.util.Iterator;

import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.form.model.Addition;
import com._1c.g5.v8.dt.form.model.AutoCommandBar;
import com._1c.g5.v8.dt.form.model.Button;
import com._1c.g5.v8.dt.form.model.ContextMenu;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormGroup;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.form.model.FormItemContainer;
import com._1c.g5.v8.dt.form.model.ManagedFormAdditionType;
import com._1c.g5.v8.dt.form.model.ManagedFormButtonType;
import com._1c.g5.v8.dt.form.model.ManagedFormGroupType;

/**
 * Navigation over the visual items of a managed form, including the parts that are not in any
 * {@code getItems()} list.
 *
 * <p>The auto command bar of a form or a table ({@code ФормаКоманднаяПанель},
 * {@code <Table>КоманднаяПанель}), context menus, extended tooltips and search additions are
 * contained by dedicated references ({@code CommandBarHolder.autoCommandBar},
 * {@code ContextMenuHolder.contextMenu}, ...). A walk over {@code getItems()} alone never reaches
 * them: the command bar could not be a parent for {@code move_item}, a button inside it could not
 * be found, and a new item id could collide with one of their ids — form item ids are unique across
 * the whole form.</p>
 *
 * <p>The button-kind rule mirrors EDT's own {@code FormItemMovementService}: a command bar, a
 * button group, a popup, a context menu and a search-control addition host command-bar buttons;
 * everything else hosts usual buttons. A usual button inside a command bar is EDT marker SU107
 * "Unsupported button type".</p>
 */
public final class FormItemTree {

    private FormItemTree() {
        // static helpers only
    }

    /**
     * Finds a visual item by id or name (case-insensitive) anywhere under {@code root}, structural
     * parts included.
     */
    public static FormItem find(EObject root, Integer id, String name) {
        if (root == null || (id == null && name == null)) {
            return null;
        }
        for (Iterator<EObject> contents = root.eAllContents(); contents.hasNext();) {
            if (contents.next() instanceof FormItem item) {
                if (id != null && item.getId() == id.intValue()) {
                    return item;
                }
                if (name != null && name.equalsIgnoreCase(item.getName())) {
                    return item;
                }
            }
        }
        return null;
    }

    /**
     * Returns the container whose {@code getItems()} holds the item, or {@code null} when the item
     * is a structural part of its owner (or is detached).
     */
    public static FormItemContainer itemsOwner(FormItem item) {
        if (item != null && item.eContainer() instanceof FormItemContainer container
                && container.getItems().contains(item)) {
            return container;
        }
        return null;
    }

    /**
     * Whether the item is a structural part of its owner — an auto command bar, a context menu, an
     * extended tooltip, an addition — rather than a free item of a {@code getItems()} list. Such a
     * part exists as long as its owner does: it can be configured and filled, but not removed or
     * moved.
     */
    public static boolean isStructuralPart(FormItem item) {
        return item != null && item.eContainer() != null && itemsOwner(item) == null;
    }

    /**
     * Default parent of a new button: the form's own auto command bar. A legacy top-level
     * command-bar group is used only when the model has no auto command bar at all.
     */
    public static FormItemContainer defaultButtonContainer(Form form) {
        if (form.getAutoCommandBar() != null) {
            return form.getAutoCommandBar();
        }
        for (FormItem item : form.getItems()) {
            if (item instanceof FormGroup group
                    && (group.getType() == ManagedFormGroupType.COMMAND_BAR
                            || group.getType() == ManagedFormGroupType.AUTO_COMMAND_BAR)) {
                return group;
            }
        }
        return form;
    }

    /** Whether buttons placed into the container must be of a command-bar kind. */
    public static boolean hostsCommandBarButtons(FormItemContainer container) {
        if (container instanceof AutoCommandBar || container instanceof ContextMenu) {
            return true;
        }
        if (container instanceof FormGroup group) {
            ManagedFormGroupType type = group.getType();
            return type == ManagedFormGroupType.COMMAND_BAR
                    || type == ManagedFormGroupType.AUTO_COMMAND_BAR
                    || type == ManagedFormGroupType.BUTTON_GROUP
                    || type == ManagedFormGroupType.POPUP;
        }
        return container instanceof Addition addition
                && addition.getType() == ManagedFormAdditionType.SEARCH_CONTROL_ADDITION;
    }

    /** Button kind EDT gives a new button in the container. */
    public static ManagedFormButtonType defaultButtonType(FormItemContainer container) {
        return hostsCommandBarButtons(container)
                ? ManagedFormButtonType.COMMAND_BAR_BUTTON
                : ManagedFormButtonType.USUAL_BUTTON;
    }

    /**
     * Converts the button kind to the one the container accepts, the way EDT does on a move:
     * a button and a hyperlink keep being a button and a hyperlink, only the command-bar flavour
     * changes.
     */
    public static void fitButtonType(Button button, FormItemContainer container) {
        ManagedFormButtonType type = button.getType();
        if (hostsCommandBarButtons(container)) {
            if (type == ManagedFormButtonType.USUAL_BUTTON) {
                button.setType(ManagedFormButtonType.COMMAND_BAR_BUTTON);
            } else if (type == ManagedFormButtonType.HYPERLINK) {
                button.setType(ManagedFormButtonType.COMMAND_BAR_HYPERLINK);
            }
        } else if (type == ManagedFormButtonType.COMMAND_BAR_BUTTON) {
            button.setType(ManagedFormButtonType.USUAL_BUTTON);
        } else if (type == ManagedFormButtonType.COMMAND_BAR_HYPERLINK) {
            button.setType(ManagedFormButtonType.HYPERLINK);
        }
    }

    /** Next free visual item id: ids are unique across the whole form, structural parts included. */
    public static int nextItemId(EObject root) {
        int maxId = root instanceof FormItem item ? item.getId() : 0;
        if (root != null) {
            for (Iterator<EObject> contents = root.eAllContents(); contents.hasNext();) {
                if (contents.next() instanceof FormItem item) {
                    maxId = Math.max(maxId, item.getId());
                }
            }
        }
        return maxId + 1;
    }

    /** Short human-readable reference for error messages: kind, name and id. */
    public static String describe(FormItem item) {
        if (item == null) {
            return "<none>"; //$NON-NLS-1$
        }
        return item.eClass().getName() + " '" + item.getName() + "' (id=" + item.getId() + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
